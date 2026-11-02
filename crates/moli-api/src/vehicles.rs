//! Human-only vehicle private views and durable journal. Orders use /api/command.
use crate::{caller::Caller, rest::Recorded, session::Sessions};
use axum::{
    Extension, Json,
    extract::{Path, Query, State},
    http::{HeaderMap, StatusCode, header::CACHE_CONTROL},
    response::{IntoResponse, Response},
};
use moli_core::DeviceId;
use moli_runtime::Hub;
use serde::Deserialize;
use serde_json::json;
use std::sync::Arc;
fn error(code: StatusCode, text: &str) -> Response {
    (code, Json(json!({"error":text}))).into_response()
}
fn allowed(hub: &Hub, id: &str, humans: &Sessions, caller: &Caller, headers: &HeaderMap) -> bool {
    humans.is_human(headers, &caller.key)
        && !caller
            .person
            .as_ref()
            .is_some_and(crate::people::PersonRef::is_guest)
        && hub
            .describe(&DeviceId::from(id))
            .is_some_and(|(d, _)| d.manufacturer.as_deref() == Some("Hyundai"))
}
#[derive(Deserialize)]
pub(crate) struct Dates {
    from: String,
    to: String,
}
pub(crate) async fn journal(
    State(hub): State<Hub>,
    Extension(humans): Extension<Arc<Sessions>>,
    Extension(Recorded(history)): Extension<Recorded>,
    caller: Caller,
    Path(id): Path<String>,
    headers: HeaderMap,
    Query(dates): Query<Dates>,
) -> Response {
    if !allowed(&hub, &id, &humans, &caller, &headers) {
        return error(StatusCode::FORBIDDEN, "A human session is required");
    }
    let Some(history) = history else {
        return error(StatusCode::SERVICE_UNAVAILABLE, "History is disabled");
    };
    match history.vehicle_report(id, dates.from, dates.to).await {
        Ok(value) => ([(CACHE_CONTROL, "no-store")], Json(value)).into_response(),
        Err(_) => error(
            StatusCode::UNPROCESSABLE_ENTITY,
            "Vehicle journal unavailable or invalid date range",
        ),
    }
}
pub(crate) async fn finance(
    State(hub): State<Hub>,
    Extension(humans): Extension<Arc<Sessions>>,
    Extension(Recorded(history)): Extension<Recorded>,
    caller: Caller,
    Path(id): Path<String>,
    headers: HeaderMap,
    Json(row): Json<moli_history::vehicle::Finance>,
) -> Response {
    if !allowed(&hub, &id, &humans, &caller, &headers) {
        return error(StatusCode::FORBIDDEN, "A human session is required");
    }
    let Some(history) = history else {
        return error(StatusCode::SERVICE_UNAVAILABLE, "History is disabled");
    };
    match history.vehicle_finance(id, row).await {
        Ok(()) => ([(CACHE_CONTROL, "no-store")], Json(json!({"saved":true}))).into_response(),
        Err(_) => error(
            StatusCode::UNPROCESSABLE_ENTITY,
            "Incomplete or invalid financial source",
        ),
    }
}
pub(crate) async fn private(
    State(hub): State<Hub>,
    Extension(humans): Extension<Arc<Sessions>>,
    caller: Caller,
    Path((id, field)): Path<(String, String)>,
    headers: HeaderMap,
) -> Response {
    if !allowed(&hub, &id, &humans, &caller, &headers) {
        return error(StatusCode::FORBIDDEN, "A human session is required");
    }
    if !matches!(field.as_str(), "gps" | "svm" | "action") {
        return error(StatusCode::NOT_FOUND, "Unknown private view");
    }
    match hub.private_read(&DeviceId::from(id), &field).await {
        Ok(value) => ([(CACHE_CONTROL, "no-store")], Json(value)).into_response(),
        Err(_) => error(
            StatusCode::SERVICE_UNAVAILABLE,
            "Private view unavailable; location opt-in, current authorization or stored image may be absent",
        ),
    }
}
