//! Volatile, on-device view only. Never Hub state, history, journal, or export.
use crate::{
    client::{Client, Session},
    model::{Vehicle, at, number, timestamp},
};
use base64::{Engine as _, engine::general_purpose::STANDARD};
use moli_runtime::{BoxFuture, media::PrivateSource};
use serde_json::{Value as Json, json};
use std::sync::Mutex;
#[derive(Default)]
pub struct Private {
    inner: Mutex<Option<Entry>>,
    navigation: Mutex<Option<Navigation>>,
    last_action: Mutex<Option<String>>,
}
struct Entry {
    session: Session,
    vehicle: Vehicle,
    gps: Json,
    received_at: u64,
    commands_enabled: bool,
}
impl std::fmt::Debug for Private {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("Private vehicle view (<redacted>)")
    }
}
struct Navigation {
    token: String,
    expires: u64,
    body: Json,
}
#[derive(serde::Deserialize)]
#[serde(deny_unknown_fields)]
struct Destination {
    latitude: f64,
    longitude: f64,
    name: String,
    consent: bool,
}
impl Private {
    async fn legacy_action(&self, session: &Session, vehicle: &Vehicle) -> anyhow::Result<Json> {
        let id = vehicle
            .id
            .as_deref()
            .ok_or_else(|| anyhow::anyhow!("vehicle unavailable"))?;
        let action = self
            .last_action
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .clone()
            .ok_or_else(|| anyhow::anyhow!("no cloud action receipt"))?;
        let mut client = Client::new();
        let h =
            Client::<crate::client::Https>::legacy_vehicle_headers(Some(session), vehicle.ccs2)?;
        let value = client
            .legacy_request(
                "GET",
                &format!("/api/v1/spa/notifications/{id}/records"),
                &h,
                &Json::Null,
            )
            .await?;
        let result = value["resMsg"]
            .as_array()
            .and_then(|a| a.iter().find(|v| v["recordId"] == action))
            .and_then(|v| v["result"].as_str());
        let state = match result {
            Some("success") => "SUCCESS",
            Some("fail") => "FAILURE",
            Some("non-response") => "TIMEOUT",
            _ => "UNKNOWN",
        };
        Ok(
            json!({"cloud_state":state,"physical_result":"not_confirmed","received_at":moli_core::now_ms()}),
        )
    }
    async fn stored_svm(
        client: &mut Client,
        session: &mut Session,
        id: &str,
        ccs2: u64,
    ) -> anyhow::Result<Json> {
        let list = client
            .gspa(
                session,
                "GET",
                &format!("svm/vehicles/{id}/na-images"),
                &Json::Null,
                false,
                ccs2,
            )
            .await?;
        let item = list["scsList"]
            .as_array()
            .and_then(|list| {
                list.iter()
                    .filter(|i| i["date"].is_string())
                    .max_by_key(|i| i["date"].as_str().unwrap_or(""))
            })
            .ok_or_else(|| anyhow::anyhow!("No stored SVM image"))?;
        let tvid = item["tvid"]
            .as_str()
            .ok_or_else(|| anyhow::anyhow!("SVM response unavailable"))?;
        crate::model::validate_id(tvid)?;
        let detail = client
            .gspa(
                session,
                "GET",
                &format!("svm/vehicles/{id}/na-images/{tvid}"),
                &Json::Null,
                false,
                ccs2,
            )
            .await?;
        let text = detail
            .pointer("/scsDetail/svmImage")
            .and_then(Json::as_str)
            .ok_or_else(|| anyhow::anyhow!("SVM image unavailable"))?;
        let bytes = STANDARD
            .decode(text)
            .map_err(|_| anyhow::anyhow!("SVM image encoding unavailable"))?;
        let bytes = strip_jpeg_metadata(&bytes)?;
        Ok(
            json!({"content_type":"image/jpeg","base64":STANDARD.encode(bytes),"image_sizes":numeric_array(detail.pointer("/scsDetail/imageSize")),"angles":numeric_array(detail.pointer("/scsDetail/validAngleofView").or_else(||detail.pointer("/scsDetail/validAngleOfView"))),"source":"latest stored Hyundai SVM; no capture requested","received_at":moli_core::now_ms()}),
        )
    }
    pub fn acknowledge(&self, reply: &Json) {
        *self
            .last_action
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner) = reply["msgId"]
            .as_str()
            .filter(|s| crate::model::validate_id(s).is_ok())
            .map(str::to_owned);
    }
    pub fn take_navigation(&self, token: &str, device: &str) -> Result<Json, crate::Error> {
        let mut guard = self
            .navigation
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        if guard
            .as_ref()
            .is_some_and(|n| n.expires <= moli_core::now_ms())
        {
            *guard = None;
        }
        if !guard
            .as_ref()
            .is_some_and(|n| n.token == token && n.expires > moli_core::now_ms())
        {
            return Err(crate::Error::Expired);
        }
        let mut n = guard.take().ok_or(crate::Error::Expired)?;
        n.body["deviceID"] = json!(device);
        Ok(n.body)
    }
    pub fn update(
        &self,
        payload: &Json,
        session: &Session,
        vehicle: &Vehicle,
        location: bool,
        commands_enabled: bool,
    ) {
        let state = payload.pointer("/state/Vehicle").unwrap_or(&Json::Null);
        let gps = if location {
            let lat = number(at(state, "Location.GeoCoord.Latitude"))
                .filter(|n| (-90.0..=90.0).contains(n));
            let lon = number(at(state, "Location.GeoCoord.Longitude"))
                .filter(|n| (-180.0..=180.0).contains(n));
            json!({"latitude":lat,"longitude":lon,"vehicle_at":timestamp(&state["Date"]),"location_at":location_time(at(state,"Location.TimeStamp")),"cloud_at":timestamp(&payload["lastUpdateTime"]),"received_at":moli_core::now_ms(),"source":"Hyundai cached location","ephemeral":true})
        } else {
            json!({"available":false,"reason":"location_disabled"})
        };
        *self
            .inner
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner) = Some(Entry {
            session: session.clone(),
            vehicle: vehicle.clone(),
            gps,
            received_at: moli_core::now_ms(),
            commands_enabled,
        });
    }
}
impl PrivateSource for Private {
    fn prepare<'a>(&'a self, field: &'a str, input: Json) -> BoxFuture<'a, anyhow::Result<Json>> {
        Box::pin(async move {
            anyhow::ensure!(field == "navigation", "unsupported private input");
            let ready = self
                .inner
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner)
                .as_ref()
                .is_some_and(|e| {
                    e.commands_enabled
                        && e.session.api_mode == crate::auth::ApiMode::Europe
                        && moli_core::now_ms().saturating_sub(e.received_at) < 3_600_000
                });
            anyhow::ensure!(ready, "private destination unavailable");
            let d: Destination = serde_json::from_value(input)
                .map_err(|_| anyhow::anyhow!("invalid destination"))?;
            anyhow::ensure!(
                d.consent
                    && d.latitude.is_finite()
                    && d.longitude.is_finite()
                    && (-90.0..=90.0).contains(&d.latitude)
                    && (-180.0..=180.0).contains(&d.longitude)
                    && d.name.len() <= 80
                    && !d.name.chars().any(char::is_control),
                "invalid destination"
            );
            let token = crate::crypto::device_id()
                .map_err(|_| anyhow::anyhow!("private preparation unavailable"))?;
            let body = json!({"poiInfoList":[{"phone":"","waypointID":1,"lang":1,"src":"HERE","coord":{"lat":d.latitude,"lon":d.longitude,"alt":0,"type":0},"addr":"","zip":"","placeid":"","name":d.name}]});
            *self
                .navigation
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner) = Some(Navigation {
                token: token.clone(),
                expires: moli_core::now_ms() + 120_000,
                body,
            });
            Ok(json!({"navigation_token":token,"expires_seconds":120}))
        })
    }
    fn read<'a>(&'a self, field: &'a str) -> BoxFuture<'a, anyhow::Result<Json>> {
        Box::pin(async move {
            let (mut session, vehicle, gps, at) = {
                let guard = self
                    .inner
                    .lock()
                    .unwrap_or_else(std::sync::PoisonError::into_inner);
                let entry = guard
                    .as_ref()
                    .ok_or_else(|| anyhow::anyhow!("Hyundai unavailable"))?;
                (
                    entry.session.clone(),
                    entry.vehicle.clone(),
                    entry.gps.clone(),
                    entry.received_at,
                )
            };
            anyhow::ensure!(
                moli_core::now_ms().saturating_sub(at) < 3_600_000,
                "cached private data expired"
            );
            if field == "gps" {
                return Ok(gps);
            }
            anyhow::ensure!(
                matches!(field, "svm" | "action"),
                "unsupported private field"
            );
            let id = vehicle
                .id
                .as_deref()
                .ok_or_else(|| anyhow::anyhow!("vehicle unavailable"))?;
            if session.api_mode == crate::auth::ApiMode::Europe {
                anyhow::ensure!(
                    field == "action",
                    "stored SVM unsupported by selected EU API"
                );
                return self.legacy_action(&session, &vehicle).await;
            }
            let mut client = Client::new();
            if field == "action" {
                let value = client
                    .gspa(
                        &mut session,
                        "GET",
                        &format!("status/vehicles/{id}/update-status?path=gspa/v1/remote/vehicles"),
                        &Json::Null,
                        false,
                        vehicle.ccs2,
                    )
                    .await?;
                let state = value["pollingState"]
                    .as_str()
                    .filter(|s| matches!(*s, "WAIT" | "SUCCESS" | "FAILURE" | "TIMEOUT"))
                    .unwrap_or("UNKNOWN");
                return Ok(
                    json!({"cloud_state":state,"physical_result":"not_confirmed","received_at":moli_core::now_ms()}),
                );
            }

            Self::stored_svm(&mut client, &mut session, id, vehicle.ccs2).await
        })
    }
}
fn strip_jpeg_metadata(bytes: &[u8]) -> anyhow::Result<Vec<u8>> {
    anyhow::ensure!(
        bytes.len() < 2 * 1024 * 1024 && bytes.starts_with(&[255, 216]),
        "Only metadata-stripped JPEG SVM is supported"
    );
    let mut out = vec![255, 216];
    let mut pos = 2;
    while pos < bytes.len() {
        anyhow::ensure!(bytes[pos] == 255 && pos + 1 < bytes.len(), "Invalid JPEG");
        let marker = bytes[pos + 1];
        if marker == 218 {
            out.extend_from_slice(&bytes[pos..]);
            return Ok(out);
        }
        anyhow::ensure!(pos + 4 <= bytes.len(), "Invalid JPEG");
        let len = usize::from(u16::from_be_bytes([bytes[pos + 2], bytes[pos + 3]]));
        anyhow::ensure!(len >= 2 && pos + 2 + len <= bytes.len(), "Invalid JPEG");
        if !(224..=239).contains(&marker) && marker != 254 {
            out.extend_from_slice(&bytes[pos..pos + 2 + len]);
        }
        pos += 2 + len;
    }
    anyhow::bail!("Invalid JPEG")
}

fn numeric_array(value: Option<&Json>) -> Json {
    value
        .and_then(Json::as_array)
        .filter(|a| a.len() <= 64 && a.iter().all(|n| number(n).is_some()))
        .map_or(Json::Null, |a| {
            json!(a.iter().filter_map(number).collect::<Vec<_>>())
        })
}

fn location_time(value: &Json) -> Option<u64> {
    if !value.is_object() {
        return timestamp(value);
    }
    let p = ["Year", "Month", "Day", "Hour", "Min", "Sec"].map(|k| value[k].as_u64());
    if p.iter().any(Option::is_none) {
        return None;
    }
    timestamp(&json!(format!(
        "{:04}{:02}{:02}{:02}{:02}{:02}",
        p[0]?, p[1]?, p[2]?, p[3]?, p[4]?, p[5]?
    )))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn location_timestamp_is_distinct_and_never_falls_back_to_now() {
        assert_eq!(
            location_time(&json!({"Year":2026,"Month":11,"Day":2,"Hour":12,"Min":30,"Sec":15})),
            timestamp(&json!("20261102123015"))
        );
        assert_eq!(location_time(&Json::Null), None);
        assert_eq!(location_time(&json!({"Year":2026,"Month":11})), None);
        assert_eq!(
            location_time(&json!({"Year":2026,"Month":13,"Day":2,"Hour":12,"Min":30,"Sec":15})),
            None
        );
    }
}
