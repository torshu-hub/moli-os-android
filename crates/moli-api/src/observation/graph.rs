//! Graph extracted from the qualified code32 prototype; official nodes only.
use super::Admission;
use moli_automation::Graph;
use serde_json::json;
pub(super) fn graph(input: &Admission, now: u64) -> Graph {
    let demand = format!("{}/declared_demand", input.source);
    let quality = format!("{}/freshness", input.source);
    let collected = format!("{}/collected_at", input.source);
    let target = format!("{}/on", input.target);
    serde_json::from_value(json!({"nodes":[
        {"id":"start","type":"manual"},
        {"id":"fresh","type":"if","all":true,"rules":[
            {"kind":"state","point":quality,"value":"cloud_recent"},
            {"kind":"state","point":collected,"op":"ge","value":now.saturating_sub(input.max_age)},
            {"kind":"state","point":collected,"op":"le","value":now}
        ]},
        {"id":"demand_on","type":"if","all":true,"rules":[{"kind":"state","point":demand,"value":"on"}]},
        {"id":"demand_off","type":"if","all":true,"rules":[{"kind":"state","point":demand,"value":"off"}]},
        {"id":"would_on","type":"set","point":target,"value":true},
        {"id":"would_off","type":"set","point":target,"value":false}
    ],"edges":[
        {"from":"start","to":"fresh"},
        {"from":"fresh","port":"yes","to":"demand_on"},
        {"from":"demand_on","port":"yes","to":"would_on"},
        {"from":"demand_on","port":"no","to":"demand_off"},
        {"from":"demand_off","port":"yes","to":"would_off"}
    ]})).expect("bounded built-in observation graph")
}
