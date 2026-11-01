//! Bounded public HTTPS with a closed failure vocabulary. No remote text, host,
//! request, headers or response body is carried by an error.
use crate::{Body, public_tls};
use http::{HeaderMap, Request, StatusCode};
use http_body_util::{BodyExt as _, Full, Limited};
use hyper::client::conn::http1;
use hyper_util::rt::TokioIo;
use rustls::pki_types::ServerName;
use std::{future::Future, time::Duration};
use tokio::{net::TcpStream, time::Instant};
use tokio_rustls::TlsConnector;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Phase {
    Dns,
    Connect,
    Tls,
    Headers,
    Body,
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Failure {
    pub phase: Phase,
    pub timeout: bool,
}

async fn bounded<T, E>(
    deadline: Instant,
    phase: Phase,
    future: impl Future<Output = Result<T, E>>,
) -> Result<T, Failure> {
    tokio::time::timeout_at(deadline, future)
        .await
        .map_err(|_| Failure {
            phase,
            timeout: true,
        })?
        .map_err(|_| Failure {
            phase,
            timeout: false,
        })
}

/// Each phase shares one total deadline, including DNS and body collection.
/// TLS always verifies the public certificate and no redirect is followed.
///
/// # Errors
/// Only the failed phase and timeout flag are returned.
pub async fn https(
    host: &str,
    mut request: Request<Full<Body>>,
    limit: Duration,
    max_body: usize,
) -> Result<(StatusCode, HeaderMap, Body), Failure> {
    let deadline = Instant::now() + limit;
    request.headers_mut().insert(
        http::header::HOST,
        host.parse().map_err(|_| Failure {
            phase: Phase::Headers,
            timeout: false,
        })?,
    );
    let addresses: Vec<_> = bounded(deadline, Phase::Dns, tokio::net::lookup_host((host, 443)))
        .await?
        .collect();
    let tcp = bounded(
        deadline,
        Phase::Connect,
        TcpStream::connect(addresses.as_slice()),
    )
    .await?;
    let name = ServerName::try_from(host.to_owned()).map_err(|_| Failure {
        phase: Phase::Tls,
        timeout: false,
    })?;
    let tls = bounded(
        deadline,
        Phase::Tls,
        TlsConnector::from(public_tls()).connect(name, tcp),
    )
    .await?;
    exchange(tls, request, deadline, max_body).await
}
async fn exchange<S>(
    stream: S,
    request: Request<Full<Body>>,
    deadline: Instant,
    max_body: usize,
) -> Result<(StatusCode, HeaderMap, Body), Failure>
where
    S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin + Send + 'static,
{
    let (mut sender, connection) = bounded(
        deadline,
        Phase::Headers,
        http1::handshake(TokioIo::new(stream)),
    )
    .await?;
    let connection = tokio::spawn(async move {
        let _ = connection.await;
    });
    // Abort on timeout/cancellation too. A hung read must not leave an orphaned
    // connection task behind across retries.
    let _guard = Connection(connection);
    let (head, body) = bounded(deadline, Phase::Headers, sender.send_request(request))
        .await?
        .into_parts();
    let body = bounded(
        deadline,
        Phase::Body,
        Limited::new(body, max_body).collect(),
    )
    .await?
    .to_bytes();
    Ok((head.status, head.headers, body))
}
struct Connection(tokio::task::JoinHandle<()>);
impl Drop for Connection {
    fn drop(&mut self) {
        self.0.abort();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::{AsyncReadExt as _, AsyncWriteExt as _};
    #[tokio::test]
    async fn real_http_exchange_bounds_body_and_cleans_up_after_a_stalled_reply() {
        for (reply, limit, expected) in [
            ("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}", 100, None),
            (
                "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}",
                1,
                Some(false),
            ),
            (
                "HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\nx",
                100,
                Some(true),
            ),
        ] {
            let (stream, mut server) = tokio::io::duplex(4096);
            let peer = tokio::spawn(async move {
                let mut request = [0_u8; 1024];
                assert!(server.read(&mut request).await.unwrap() > 0);
                server.write_all(reply.as_bytes()).await.unwrap();
                // A bounded failure must drop the connection, too.
                tokio::time::timeout(Duration::from_secs(2), server.read(&mut request))
                    .await
                    .unwrap()
                    .unwrap()
            });
            let result = exchange(
                stream,
                Request::new(Full::new(Body::new())),
                Instant::now() + Duration::from_millis(100),
                limit,
            )
            .await;
            if let Some(timeout) = expected {
                assert_eq!(
                    result.unwrap_err(),
                    Failure {
                        phase: Phase::Body,
                        timeout
                    }
                );
            } else {
                assert_eq!(result.unwrap().2, Body::from_static(b"{}"));
            }
            assert_eq!(peer.await.unwrap(), 0);
        }
    }
    #[tokio::test]
    async fn deadline_and_phase_errors_are_closed_and_share_the_total_budget() {
        let deadline = Instant::now() + Duration::from_millis(10);
        assert_eq!(
            bounded(deadline, Phase::Dns, async { Ok::<_, ()>(42) }).await,
            Ok(42)
        );
        assert_eq!(
            bounded(deadline, Phase::Connect, async {
                Err::<(), _>("private address")
            })
            .await,
            Err(Failure {
                phase: Phase::Connect,
                timeout: false
            })
        );
        assert_eq!(
            bounded(
                deadline,
                Phase::Body,
                std::future::pending::<Result<(), ()>>()
            )
            .await,
            Err(Failure {
                phase: Phase::Body,
                timeout: true
            })
        );
        assert_eq!(
            bounded(
                deadline,
                Phase::Headers,
                std::future::pending::<Result<(), ()>>()
            )
            .await,
            Err(Failure {
                phase: Phase::Headers,
                timeout: true
            })
        );
    }
}
