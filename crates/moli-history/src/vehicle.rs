//! Durable vehicle summaries in the existing history database. No acquisition task.
//! Only typed, non-geolocated aggregates cross this boundary; raw samples retain
//! their ordinary retention. Amounts are EUR, vehicle energy Wh, grid energy kWh.
use crate::{HistoryError, Store};
use rusqlite::{OptionalExtension, params};
use serde::{Deserialize, Serialize};
use serde_json::{Value as Json, json};

pub(crate) const SCHEMA: &str = r"
CREATE TABLE IF NOT EXISTS vehicle_days(vehicle TEXT NOT NULL,day TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(vehicle,day));
CREATE TABLE IF NOT EXISTS vehicle_last(vehicle TEXT PRIMARY KEY,payload TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS vehicle_intervals(vehicle TEXT NOT NULL,id INTEGER NOT NULL,kind TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(vehicle,id,kind));
CREATE TABLE IF NOT EXISTS vehicle_finance(vehicle TEXT NOT NULL,id TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(vehicle,id));
";
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(deny_unknown_fields)]
pub struct DrivingDay {
    pub day: String,
    pub distance_km: Option<f64>,
    pub consumed_wh: Option<f64>,
    pub motor_wh: Option<f64>,
    pub climate_wh: Option<f64>,
    pub electronics_wh: Option<f64>,
    pub battery_management_wh: Option<f64>,
    pub regenerated_wh: Option<f64>,
    pub source: String,
    pub received_at: u64,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct VehicleObservation {
    pub at: u64,
    pub received_at: u64,
    pub soc: Option<f64>,
    pub odometer_km: Option<f64>,
    pub charging: Option<bool>,
    pub consumed_wh: Option<f64>,
    pub weather: Option<Weather>,
    pub grid: Option<Grid>,
    pub max_gap_ms: u64,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Weather {
    pub point: String,
    pub at: u64,
    pub celsius: f64,
}
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
struct Daily {
    day: String,
    provider: Option<DrivingDay>,
    first: Option<VehicleObservation>,
    last: Option<VehicleObservation>,
    weather: Option<WeatherSummary>,
    gap: bool,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Grid {
    pub point: String,
    pub at: u64,
    pub kwh: f64,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
struct WeatherSummary {
    point: String,
    first: u64,
    last: u64,
    count: u32,
    sum: f64,
    min: f64,
    max: f64,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
struct Progress {
    last: VehicleObservation,
    charge_start: Option<VehicleObservation>,
    last_charge_end: Option<VehicleObservation>,
    #[serde(default)]
    gap: bool,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Finance {
    /// Caller-chosen stable id for idempotent correction, never an address/name.
    pub id: String,
    pub day: String,
    pub from_ms: u64,
    pub to_ms: u64,
    pub kind: String, // home or external; no inferred place from GPS
    pub grid_kwh: Option<f64>,
    pub hp_kwh: Option<f64>,
    pub hc_kwh: Option<f64>,
    pub hp_eur_kwh: Option<f64>,
    pub hc_eur_kwh: Option<f64>,
    pub paid_eur: Option<f64>,
    pub tariff_from: String,
    pub source: String,
    pub measured: bool,
    #[serde(default)]
    pub driving_price_eur_kwh: Option<f64>,
}
fn valid_day(s: &str) -> bool {
    s.len() == 10 && s.parse::<jiff::civil::Date>().is_ok()
}
fn valid_key(s: &str) -> bool {
    !s.is_empty()
        && s.len() <= 128
        && s.bytes()
            .all(|c| c.is_ascii_alphanumeric() || b"_-:.".contains(&c))
}
fn valid_point(s: &str) -> bool {
    s.len() <= 128 && moli_core::PointId::from(s).split().is_some()
}
fn nonnegative(v: Option<f64>) -> bool {
    v.is_none_or(|v| v.is_finite() && (0.0..=1e12).contains(&v))
}
fn fail() -> HistoryError {
    HistoryError::Internal("invalid vehicle journal input".into())
}
fn encode<T: Serialize>(v: &T) -> Result<String, HistoryError> {
    serde_json::to_string(v).map_err(|_| fail())
}
fn decode<T: serde::de::DeserializeOwned>(s: &str) -> Result<T, HistoryError> {
    serde_json::from_str(s).map_err(|_| fail())
}
fn day(at: u64) -> Result<String, HistoryError> {
    let t = jiff::Timestamp::from_millisecond(i64::try_from(at).map_err(|_| fail())?)
        .map_err(|_| fail())?;
    Ok(t.in_tz("Europe/Paris")
        .map_err(|_| fail())?
        .date()
        .to_string())
}
fn delta(a: Option<f64>, b: Option<f64>, max: f64) -> Option<f64> {
    a.zip(b)
        .map(|(a, b)| b - a)
        .filter(|d| d.is_finite() && (0.0..=max).contains(d))
}
impl Store {
    pub fn vehicle_day(&self, vehicle: &str, row: &DrivingDay) -> Result<(), HistoryError> {
        if !valid_key(vehicle)
            || !valid_day(&row.day)
            || [
                row.distance_km,
                row.consumed_wh,
                row.motor_wh,
                row.climate_wh,
                row.electronics_wh,
                row.battery_management_wh,
                row.regenerated_wh,
            ]
            .iter()
            .any(|v| !nonnegative(*v))
        {
            return Err(fail());
        }
        let conn = self
            .writer
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        let old: Option<String> = conn
            .query_row(
                "SELECT payload FROM vehicle_days WHERE vehicle=?1 AND day=?2",
                params![vehicle, row.day],
                |r| r.get(0),
            )
            .optional()?;
        let mut daily: Daily = old
            .map(|s| decode(&s))
            .transpose()?
            .unwrap_or_else(|| Daily {
                day: row.day.clone(),
                ..Daily::default()
            });
        // A reread replaces the provider's cumulative daily total, never adds it.
        daily.provider = Some(row.clone());
        conn.execute(
            "INSERT OR REPLACE INTO vehicle_days VALUES(?1,?2,?3)",
            params![vehicle, row.day, encode(&daily)?],
        )?;
        Ok(())
    }
    #[allow(clippy::too_many_lines)] // One atomic transition writes day, charge edges and cursor.
    pub fn vehicle_observe(
        &self,
        vehicle: &str,
        mut row: VehicleObservation,
    ) -> Result<(), HistoryError> {
        if !valid_key(vehicle)
            || row.at > row.received_at.saturating_add(1000)
            || row.received_at.saturating_sub(row.at) > row.max_gap_ms
            || [row.soc, row.odometer_km, row.consumed_wh]
                .iter()
                .any(|v| !nonnegative(*v))
        {
            return Err(fail());
        }
        if row.weather.as_ref().is_some_and(|w| {
            !valid_point(&w.point)
                || !w.celsius.is_finite()
                || !(-100.0..=70.0).contains(&w.celsius)
                || w.at > row.received_at
                || row.received_at - w.at > row.max_gap_ms
        }) {
            row.weather = None;
        }
        if row.grid.as_ref().is_some_and(|g| {
            !valid_point(&g.point)
                || !g.kwh.is_finite()
                || g.kwh < 0.0
                || g.at > row.received_at
                || row.received_at - g.at > row.max_gap_ms
        }) {
            row.grid = None;
        }
        let mut conn = self
            .writer
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        let tx = conn.transaction()?;
        let old: Option<String> = tx
            .query_row(
                "SELECT payload FROM vehicle_last WHERE vehicle=?1",
                [vehicle],
                |r| r.get(0),
            )
            .optional()?;
        let previous: Option<Progress> = old.map(|s| decode(&s)).transpose()?;
        if previous.as_ref().is_some_and(|p| row.at <= p.last.at) {
            return Ok(());
        } // cache duplicate/out of order
        let date = day(row.at)?;
        let old: Option<String> = tx
            .query_row(
                "SELECT payload FROM vehicle_days WHERE vehicle=?1 AND day=?2",
                params![vehicle, date],
                |r| r.get(0),
            )
            .optional()?;
        let mut daily: Daily = old
            .map(|s| decode(&s))
            .transpose()?
            .unwrap_or_else(|| Daily {
                day: date.clone(),
                ..Daily::default()
            });
        daily.first.get_or_insert_with(|| row.clone());
        daily.last = Some(row.clone());
        let gap = previous
            .as_ref()
            .is_some_and(|p| row.at.saturating_sub(p.last.at) > row.max_gap_ms);
        daily.gap |= gap;
        if let Some(w) = &row.weather {
            match &mut daily.weather {
                Some(a) if a.point == w.point && w.at > a.last => {
                    a.last = w.at;
                    a.count += 1;
                    a.sum += w.celsius;
                    a.min = a.min.min(w.celsius);
                    a.max = a.max.max(w.celsius);
                }
                None => {
                    daily.weather = Some(WeatherSummary {
                        point: w.point.clone(),
                        first: w.at,
                        last: w.at,
                        count: 1,
                        sum: w.celsius,
                        min: w.celsius,
                        max: w.celsius,
                    });
                }
                _ => {}
            }
        }
        let mut progress = previous.unwrap_or_else(|| Progress {
            last: row.clone(),
            charge_start: None,
            last_charge_end: None,
            gap: false,
        });
        progress.gap |= gap || progress.last.charging.is_none() || row.charging.is_none();
        if progress.last.charging != Some(true)
            && row.charging == Some(true)
            && progress.charge_start.is_none()
        {
            if let Some(start) = &progress.last_charge_end {
                let km = delta(start.odometer_km, row.odometer_km, 5_000.0);
                let wh = delta(start.consumed_wh, row.consumed_wh, 500_000.0);
                let item = json!({"kind":"between_charges","from":start.at,"to":row.at,"distance_km":km,"consumed_wh":wh,"kwh_per_100km":km.zip(wh).filter(|(km,_)|*km>0.0).map(|(km,wh)|wh/10.0/km),"quality":"observed_boundaries_incomplete","gap":progress.gap,"cost_eur":null});
                tx.execute(
                    "INSERT OR REPLACE INTO vehicle_intervals VALUES(?1,?2,?3,?4)",
                    params![
                        vehicle,
                        i64::try_from(start.at).map_err(|_| fail())?,
                        "between_charges",
                        item.to_string()
                    ],
                )?;
            }
            progress.charge_start = Some(row.clone());
        }
        if row.charging == Some(false)
            && (progress.last.charging == Some(true) || progress.charge_start.is_some())
        {
            let start = progress.charge_start.take();
            let id = start.as_ref().map_or(row.at, |r| r.at);
            let item = json!({"kind":"charge","from":start.as_ref().map(|r|r.at),"to":row.at,"soc_start":start.as_ref().and_then(|r|r.soc),"soc_end":row.soc,"grid_kwh":start.as_ref().and_then(|s|s.grid.as_ref()).zip(row.grid.as_ref()).filter(|(a,b)|a.point==b.point&&b.at>a.at).and_then(|(a,b)|delta(Some(a.kwh),Some(b.kwh),1000.0)),"cost_eur":null,"quality":"observed_boundaries_incomplete","gap":progress.gap,"source":"Hyundai cached status; grid energy not inferred from SOC"});
            tx.execute(
                "INSERT OR REPLACE INTO vehicle_intervals VALUES(?1,?2,?3,?4)",
                params![
                    vehicle,
                    i64::try_from(id).map_err(|_| fail())?,
                    "charge",
                    item.to_string()
                ],
            )?;
            progress.last_charge_end = Some(row.clone());
            progress.gap = false;
        }
        progress.last = row;
        tx.execute(
            "INSERT OR REPLACE INTO vehicle_days VALUES(?1,?2,?3)",
            params![vehicle, date, encode(&daily)?],
        )?;
        tx.execute(
            "INSERT OR REPLACE INTO vehicle_last VALUES(?1,?2)",
            params![vehicle, encode(&progress)?],
        )?;
        tx.commit()?;
        Ok(())
    }
    pub fn vehicle_finance(&self, vehicle: &str, row: &Finance) -> Result<(), HistoryError> {
        if !valid_key(vehicle)
            || !valid_key(&row.id)
            || !valid_day(&row.day)
            || row.from_ms >= row.to_ms
            || row.to_ms - row.from_ms > 7 * 86_400_000
            || !matches!(row.kind.as_str(), "home" | "external")
            || !matches!(
                row.source.as_str(),
                "invoice" | "dedicated_meter" | "owner_estimate"
            )
            || !valid_day(&row.tariff_from)
            || row.tariff_from > row.day
            || [
                row.grid_kwh,
                row.hp_kwh,
                row.hc_kwh,
                row.hp_eur_kwh,
                row.hc_eur_kwh,
                row.paid_eur,
                row.driving_price_eur_kwh,
            ]
            .iter()
            .any(|v| !nonnegative(*v))
            || row.measured == (row.source == "owner_estimate")
        {
            return Err(fail());
        }
        if let (Some(total), Some(hp), Some(hc)) = (row.grid_kwh, row.hp_kwh, row.hc_kwh)
            && (total - hp - hc).abs() > 0.01
        {
            return Err(fail());
        }
        let conn = self
            .writer
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        conn.execute(
            "INSERT OR REPLACE INTO vehicle_finance VALUES(?1,?2,?3)",
            params![vehicle, row.id, encode(row)?],
        )?;
        Ok(())
    }
    /// Archive an existing weather observation even if the parked vehicle's
    /// cache has not advanced. No extra acquisition or fabricated timestamp.
    pub fn vehicle_weather(&self, vehicle: &str, w: &Weather) -> Result<(), HistoryError> {
        if !valid_key(vehicle)
            || !valid_point(&w.point)
            || !w.celsius.is_finite()
            || !(-100.0..=70.0).contains(&w.celsius)
        {
            return Err(fail());
        }
        let key = day(w.at)?;
        let conn = self
            .writer
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        let old: Option<String> = conn
            .query_row(
                "SELECT payload FROM vehicle_days WHERE vehicle=?1 AND day=?2",
                params![vehicle, key],
                |r| r.get(0),
            )
            .optional()?;
        let mut row: Daily = old
            .map(|s| decode(&s))
            .transpose()?
            .unwrap_or_else(|| Daily {
                day: key.clone(),
                ..Daily::default()
            });
        match &mut row.weather {
            Some(a) if a.point == w.point && w.at > a.last => {
                a.last = w.at;
                a.count += 1;
                a.sum += w.celsius;
                a.min = a.min.min(w.celsius);
                a.max = a.max.max(w.celsius);
            }
            None => {
                row.weather = Some(WeatherSummary {
                    point: w.point.clone(),
                    first: w.at,
                    last: w.at,
                    count: 1,
                    sum: w.celsius,
                    min: w.celsius,
                    max: w.celsius,
                });
            }
            _ => return Ok(()),
        }
        conn.execute(
            "INSERT OR REPLACE INTO vehicle_days VALUES(?1,?2,?3)",
            params![vehicle, key, encode(&row)?],
        )?;
        Ok(())
    }
    /// Includes explicit gaps; independent of raw-sample retention. JSON export is
    /// the same typed representation rendered by Moli, with provenance and units.
    pub fn vehicle_report(
        &self,
        vehicle: &str,
        from: &str,
        to: &str,
    ) -> Result<Json, HistoryError> {
        if !valid_key(vehicle) || !valid_day(from) || !valid_day(to) || from > to {
            return Err(fail());
        }
        let conn = self
            .reader
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        let mut stmt=conn.prepare("SELECT payload FROM vehicle_days WHERE vehicle=?1 AND day>=?2 AND day<=?3 ORDER BY day LIMIT 3660")?;
        let rows: Vec<Daily> = stmt
            .query_map(params![vehicle, from, to], |r| r.get::<_, String>(0))?
            .map(|r| decode(&r?))
            .collect::<Result<_, _>>()?;
        let mut stmt = conn.prepare(
            "SELECT payload FROM vehicle_finance WHERE vehicle=?1 ORDER BY id LIMIT 10000",
        )?;
        let finance: Vec<Finance> = stmt
            .query_map([vehicle], |r| r.get::<_, String>(0))?
            .map(|r| decode(&r?))
            .collect::<Result<_, _>>()?;
        let mut days = Vec::new();
        let mut date = from.parse::<jiff::civil::Date>().map_err(|_| fail())?;
        for _ in 0..3660 {
            let key = date.to_string();
            if key.as_str() > to {
                break;
            }
            let row = rows.iter().find(|r| r.day == key);
            let p = row.and_then(|r| r.provider.as_ref());
            let km = p.and_then(|r| r.distance_km);
            let wh = p.and_then(|r| r.consumed_wh);
            let costs: Vec<_> = finance
                .iter()
                .filter(|r| r.day == key)
                .map(|r| {
                    let cost = r
                        .paid_eur
                        .or_else(|| Some(r.hp_kwh? * r.hp_eur_kwh? + r.hc_kwh? * r.hc_eur_kwh?));
                    let mut v = serde_json::to_value(r).unwrap_or(Json::Null);
                    v["cost_eur"] = json!(cost);
                    v["quality"] = json!(if cost.is_none() {
                        "incomplete"
                    } else if r.measured {
                        "documented"
                    } else {
                        "estimated"
                    });
                    v
                })
                .collect();
            let weather=row.and_then(|r|r.weather.as_ref()).map(|w|json!({"source_point":w.point,"first":w.first,"last":w.last,"count":w.count,"mean_celsius":w.sum/f64::from(w.count),"min_celsius":w.min,"max_celsius":w.max}));
            let estimated_price = price_for(&finance, &key, &key);
            let driving_cost_estimated = wh
                .zip(estimated_price)
                .map(|(wh, price)| wh / 1000.0 * price);
            days.push(json!({"day":key,"status":if km==Some(0.0){"no_driving_reported"}else if p.is_some(){"provider_daily"}else if row.is_some_and(|r|r.first.is_some()){"observations_only"}else if row.is_some(){"weather_only"}else{"collection_gap"},"provider":p,"kwh_per_100km":km.zip(wh).filter(|(km,_)|*km>0.0).map(|(km,wh)|wh/10.0/km),"observed":row,"weather":weather,"charges":costs,"driving_cost_eur":null,"estimated_driving_cost_eur":driving_cost_estimated,"estimated_driving_eur_100km":driving_cost_estimated.zip(km).filter(|(_,km)|*km>0.0).map(|(cost,km)|cost*100.0/km),"driving_estimate_basis":"explicit owner price hypothesis, excludes charge losses and residual battery mix","driving_cost_reason":"battery energy price and residual mix unknown"}));
            date = date.tomorrow().map_err(|_| fail())?;
        }
        let mut stmt = conn.prepare(
            "SELECT payload FROM vehicle_intervals WHERE vehicle=?1 ORDER BY id DESC LIMIT 1000",
        )?;
        let mut intervals: Vec<Json> = stmt
            .query_map([vehicle], |r| r.get::<_, String>(0))?
            .map(|r| decode(&r?))
            .collect::<Result<_, _>>()?;
        let last: Option<String> = conn
            .query_row(
                "SELECT payload FROM vehicle_last WHERE vehicle=?1",
                [vehicle],
                |r| r.get(0),
            )
            .optional()?;
        for interval in &mut intervals {
            annotate_estimate(interval, &finance);
        }
        let progress: Option<Progress> = last.map(|s| decode(&s)).transpose()?;
        let charging = progress
            .as_ref()
            .is_some_and(|p| p.last.charging == Some(true) || p.charge_start.is_some());
        let current_charge = progress.as_ref().filter(|_| charging).map(|p| {
            let start = p.charge_start.as_ref();
            json!({"kind":"charge_in_progress","from":start.map(|s|s.at),"to":p.last.at,"soc_start":start.and_then(|s|s.soc),"soc_end":p.last.soc,"grid_kwh":start.and_then(|s|s.grid.as_ref()).zip(p.last.grid.as_ref()).filter(|(a,b)|a.point==b.point&&b.at>a.at).and_then(|(a,b)|delta(Some(a.kwh),Some(b.kwh),1000.0)),"quality":"observed_boundaries_incomplete","gap":p.gap,"cost_eur":null})
        });
        let current=progress.filter(|_| !charging).and_then(|p|{let s=p.last_charge_end?;Some(json!({"kind":"since_last_charge","from":s.at,"to":p.last.at,"distance_km":delta(s.odometer_km,p.last.odometer_km,5_000.0),"consumed_wh":delta(s.consumed_wh,p.last.consumed_wh,500_000.0),"quality":"observed_boundaries_incomplete","gap":p.gap,"cost_eur":null}))});
        Ok(
            json!({"schema":1,"vehicle":vehicle,"timezone":"Europe/Paris","provider_day_timezone":"UTC in reference parser; provider day boundaries unconfirmed","units":{"vehicle_energy":"Wh","grid_energy":"kWh","distance":"km","money":"EUR"},"days":days,"intervals":intervals,"current_charge":current_charge,"current_interval":current.map(|mut v|{annotate_estimate(&mut v,&finance);v}),"rolling_24h":{"quality":"unavailable_without_exact_boundary_samples"},"gaps":"Absent Hyundai day is unknown; provider zero distance is no driving. SOC is never converted to kWh.","grid_source":"Only explicitly bound dedicated EV meter or owner invoice; whole-home Linky and ordinary Meross are never assigned automatically."}),
        )
    }
}

fn price_for(rows: &[Finance], from: &str, to: &str) -> Option<f64> {
    let last = rows
        .iter()
        .filter(|r| r.tariff_from.as_str() <= from && r.driving_price_eur_kwh.is_some())
        .map(|r| r.tariff_from.as_str())
        .max()?;
    let prices = rows
        .iter()
        .filter(|r| r.tariff_from.as_str() >= last && r.tariff_from.as_str() <= to)
        .filter_map(|r| r.driving_price_eur_kwh)
        .collect::<Vec<_>>();
    let price = *prices.first()?;
    prices
        .iter()
        .all(|p| (*p - price).abs() < 1e-9)
        .then_some(price)
}
fn annotate_estimate(value: &mut Json, finance: &[Finance]) {
    let price = value["from"]
        .as_u64()
        .zip(value["to"].as_u64())
        .and_then(|(a, b)| Some((day(a).ok()?, day(b).ok()?)))
        .and_then(|(a, b)| price_for(finance, &a, &b));
    let wh = value["consumed_wh"].as_f64();
    let km = value["distance_km"].as_f64();
    let cost = price.zip(wh).map(|(p, wh)| p * wh / 1000.0);
    value["estimated_driving_cost_eur"] = json!(cost);
    value["estimated_driving_eur_100km"] = json!(
        cost.zip(km)
            .filter(|(_, km)| *km > 0.0)
            .map(|(c, km)| c * 100.0 / km)
    );
    value["energy_basis"] =
        json!("provider counter delta; period/epoch unconfirmed, observed boundaries incomplete");
    value["estimate_basis"] = json!(
        "explicit dated owner price hypothesis; excludes charge losses and residual battery mix; tariff changes without split remain unknown"
    );
}

#[cfg(test)]
mod tests {
    use super::*;
    fn store() -> (Store, std::path::PathBuf) {
        static NEXT: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
        let dir = std::env::temp_dir().join(format!(
            "moli-vehicle-test-{}-{}-{}",
            std::process::id(),
            moli_core::now_ms(),
            NEXT.fetch_add(1, std::sync::atomic::Ordering::Relaxed)
        ));
        std::fs::create_dir_all(&dir).unwrap();
        (Store::open(&dir.join("history.db")).unwrap(), dir)
    }
    fn at(day: &str) -> u64 {
        u64::try_from(
            format!("{day}T12:00:00Z")
                .parse::<jiff::Timestamp>()
                .unwrap()
                .as_millisecond(),
        )
        .unwrap()
    }
    #[test]
    fn unchanged_observations_keep_new_boundaries_and_reopen_without_duplication() {
        let (store, dir) = store();
        let start = at("2026-11-01");
        let mut row = VehicleObservation {
            at: start,
            received_at: start,
            soc: Some(50.0),
            odometer_km: Some(1000.0),
            charging: Some(false),
            consumed_wh: Some(10.0),
            weather: None,
            grid: None,
            max_gap_ms: 3_600_000,
        };
        store
            .vehicle_observe("hyundai:v_test", row.clone())
            .unwrap();
        row.at += 900_000;
        row.received_at = row.at;
        store
            .vehicle_observe("hyundai:v_test", row.clone())
            .unwrap();
        drop(store);
        let store = Store::open(&dir.join("history.db")).unwrap();
        store
            .vehicle_observe("hyundai:v_test", row.clone())
            .unwrap();
        let payload: String = store
            .writer
            .lock()
            .unwrap()
            .query_row(
                "SELECT payload FROM vehicle_last WHERE vehicle=?1",
                ["hyundai:v_test"],
                |r| r.get(0),
            )
            .unwrap();
        let progress: Progress = decode(&payload).unwrap();
        assert_eq!(progress.last.at, row.at);
        assert_eq!(progress.last.received_at, row.received_at);
        drop(store);
        std::fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn daily_archive_is_idempotent_survives_raw_prune_and_distinguishes_zero_from_absence() {
        let (store, dir) = store();
        let row = DrivingDay {
            day: "2026-11-01".into(),
            distance_km: Some(100.0),
            consumed_wh: Some(16_000.0),
            regenerated_wh: Some(3_000.0),
            source: "synthetic provider".into(),
            received_at: at("2026-11-01"),
            ..DrivingDay::default()
        };
        store.vehicle_day("hyundai:v_test", &row).unwrap();
        store.vehicle_day("hyundai:v_test", &row).unwrap();
        let mut zero = row.clone();
        zero.day = "2026-11-02".into();
        zero.distance_km = Some(0.0);
        store.vehicle_day("hyundai:v_test", &zero).unwrap();
        store.prune(u64::MAX / 2).unwrap();
        let v = store
            .vehicle_report("hyundai:v_test", "2026-11-01", "2026-11-03")
            .unwrap();
        assert_eq!(v["days"].as_array().unwrap().len(), 3);
        assert_eq!(v["days"][0]["kwh_per_100km"], 16.0);
        assert_eq!(v["days"][1]["status"], "no_driving_reported");
        assert_eq!(v["days"][2]["status"], "collection_gap");
        assert!(v["days"][2]["provider"].is_null());
        drop(store);
        std::fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn charges_deltas_weather_and_costs_are_scoped_and_do_not_guess_soc_energy() {
        let (store, dir) = store();
        let at = at("2026-10-31");
        let row = VehicleObservation {
            at,
            received_at: at,
            soc: Some(50.0),
            odometer_km: Some(1000.0),
            charging: Some(false),
            consumed_wh: Some(100_000.0),
            weather: Some(Weather {
                point: "weather:outside/temperature".into(),
                at,
                celsius: 10.0,
            }),
            grid: Some(Grid {
                point: "ev:meter/energy".into(),
                at,
                kwh: 100.0,
            }),
            max_gap_ms: 3_600_000,
        };
        store
            .vehicle_observe("hyundai:v_test", row.clone())
            .unwrap();
        store
            .vehicle_observe("hyundai:v_test", row.clone())
            .unwrap();
        let mut row = row;
        row.at += 1;
        row.received_at = row.at;
        row.charging = Some(true);
        store
            .vehicle_observe("hyundai:v_test", row.clone())
            .unwrap();
        let mut end = row.clone();
        end.at += 900_000;
        end.received_at = end.at;
        end.charging = Some(false);
        end.soc = Some(80.0);
        end.grid = Some(Grid {
            point: "ev:meter/energy".into(),
            at: end.at,
            kwh: 120.0,
        });
        store
            .vehicle_observe("hyundai:v_test", end.clone())
            .unwrap();
        let mut drive = end.clone();
        drive.at += 900_000;
        drive.received_at = drive.at;
        drive.charging = Some(true);
        drive.odometer_km = Some(1100.0);
        drive.consumed_wh = Some(116_000.0);
        store.vehicle_observe("hyundai:v_test", drive).unwrap();
        let report = store
            .vehicle_report("hyundai:v_test", "2026-10-31", "2026-10-31")
            .unwrap();
        assert!(
            report["current_interval"].is_null(),
            "completed driving interval must not also appear as current during charge"
        );
        assert_eq!(report["current_charge"]["kind"], "charge_in_progress");
        assert_eq!(report["intervals"][0]["distance_km"], 100.0);
        assert_eq!(report["intervals"][0]["consumed_wh"], 16_000.0);
        assert_eq!(report["intervals"][1]["grid_kwh"], 20.0);
        assert!(report["intervals"][1]["cost_eur"].is_null());
        assert_eq!(report["days"][0]["weather"]["count"], 1);
        assert_eq!(report["days"][0]["weather"]["mean_celsius"], 10.0);
        assert!(!report.to_string().contains("Latitude"));
        drop(store);
        std::fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn documented_hp_hc_cost_and_price_hypothesis_stay_distinct() {
        let (store, dir) = store();
        let daily = DrivingDay {
            day: "2026-11-01".into(),
            distance_km: Some(100.0),
            consumed_wh: Some(16_000.0),
            ..DrivingDay::default()
        };
        store.vehicle_day("hyundai:v_test", &daily).unwrap();
        let mut row = Finance {
            id: "synthetic-receipt".into(),
            day: daily.day.clone(),
            from_ms: 1,
            to_ms: 2,
            kind: "home".into(),
            grid_kwh: Some(20.0),
            hp_kwh: Some(5.0),
            hc_kwh: Some(15.0),
            hp_eur_kwh: Some(0.25),
            hc_eur_kwh: Some(0.2),
            paid_eur: None,
            tariff_from: daily.day.clone(),
            source: "dedicated_meter".into(),
            measured: true,
            driving_price_eur_kwh: Some(0.2),
        };
        store.vehicle_finance("hyundai:v_test", &row).unwrap();
        store.vehicle_finance("hyundai:v_test", &row).unwrap();
        let v = store
            .vehicle_report("hyundai:v_test", &daily.day, &daily.day)
            .unwrap();
        assert_eq!(v["days"][0]["charges"].as_array().unwrap().len(), 1);
        assert_eq!(v["days"][0]["charges"][0]["cost_eur"], 4.25);
        assert_eq!(v["days"][0]["estimated_driving_cost_eur"], 3.2);
        assert!(v["days"][0]["driving_cost_eur"].is_null());
        assert_eq!(
            price_for(&[row.clone()], "2026-11-02", "2026-11-03"),
            Some(0.2)
        );
        assert_eq!(price_for(&[row.clone()], "2026-10-31", "2026-11-01"), None);
        let mut later = row.clone();
        later.tariff_from = "2026-11-02".into();
        later.driving_price_eur_kwh = Some(0.3);
        assert_eq!(
            price_for(&[row.clone(), later.clone()], "2026-11-01", "2026-11-02"),
            None
        );
        assert_eq!(
            price_for(&[row.clone(), later], "2026-11-02", "2026-11-03"),
            Some(0.3)
        );
        row.hp_kwh = Some(10.0);
        assert!(store.vehicle_finance("hyundai:v_test", &row).is_err());
        drop(store);
        std::fs::remove_dir_all(dir).unwrap();
    }
}
