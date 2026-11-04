//! Vehicle journal and explicit cross-integration composition. The provider supplies
//! every dated observation, even unchanged values; Store deduplicates acquisition time.
//! Storage failures are isolated from provider acquisition and human command guards.
use crate::{
    Store,
    vehicle::{DrivingDay, VehicleObservation, Weather},
};
use moli_core::{DeviceId, Unit, now_ms};
use moli_runtime::DriverCtx;
use std::path::PathBuf;
#[derive(Debug)]
pub struct Journal {
    path: PathBuf,
    store: Option<Store>,
}
impl Journal {
    #[must_use]
    pub fn new(path: PathBuf) -> Self {
        Self { path, store: None }
    }
    fn write(
        &mut self,
        ctx: &DriverCtx,
        f: impl FnOnce(&Store) -> Result<(), crate::HistoryError>,
    ) {
        if self.try_write(f).is_err() {
            ctx.diagnostic("vehicle_journal_unavailable", "storage", 900);
        }
    }
    fn try_write(
        &mut self,
        f: impl FnOnce(&Store) -> Result<(), crate::HistoryError>,
    ) -> Result<(), crate::HistoryError> {
        if self.store.is_none() {
            self.store = Some(Store::open(&self.path)?);
        }
        let result = f(self.store.as_ref().expect("opened store"));
        if result.is_err() {
            self.store = None;
        }
        result
    }
    pub fn day(&mut self, ctx: &DriverCtx, id: &DeviceId, day: &DrivingDay) {
        self.write(ctx, |store| store.vehicle_day(id.as_str(), day));
    }
    pub fn weather(&mut self, ctx: &DriverCtx, id: &DeviceId, point: Option<&str>, age: u64) {
        if let Some(w) = point.and_then(|p| weather(ctx, p, now_ms(), age)) {
            self.write(ctx, |store| store.vehicle_weather(id.as_str(), &w));
        }
    }
    pub fn observe(
        &mut self,
        ctx: &DriverCtx,
        id: &DeviceId,
        mut row: VehicleObservation,
        weather_point: Option<&str>,
        meter_point: Option<&str>,
    ) {
        row.weather = weather_point.and_then(|p| weather(ctx, p, row.received_at, row.max_gap_ms));
        row.grid = meter_point.and_then(|p| grid(ctx, p, row.received_at, row.max_gap_ms));
        self.write(ctx, |store| store.vehicle_observe(id.as_str(), row));
    }
}
fn weather(ctx: &DriverCtx, point: &str, now: u64, age: u64) -> Option<Weather> {
    let (sample, unit) = ctx.observe(&moli_core::PointId::from(point))?;
    let celsius = composition_value(&sample, unit.as_ref(), &Unit::Celsius, now, age)?;
    Some(Weather {
        point: point.into(),
        at: sample.ts,
        celsius,
    })
}
fn grid(ctx: &DriverCtx, point: &str, now: u64, age: u64) -> Option<crate::vehicle::Grid> {
    let (sample, unit) = ctx.observe(&moli_core::PointId::from(point))?;
    let kwh = composition_value(&sample, unit.as_ref(), &Unit::KiloWattHour, now, age)?;
    Some(crate::vehicle::Grid {
        point: point.into(),
        at: sample.ts,
        kwh,
    })
}

fn composition_value(
    sample: &moli_core::Sample,
    unit: Option<&Unit>,
    expected: &Unit,
    now: u64,
    age: u64,
) -> Option<f64> {
    if unit != Some(expected) || sample.ts > now || now.saturating_sub(sample.ts) > age {
        return None;
    }
    sample.value.as_f64().filter(|v| v.is_finite())
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn storage_failure_recovers_without_changing_existing_rows() {
        let root =
            std::env::temp_dir().join(format!("moli-journal-recovery-{}", std::process::id()));
        std::fs::create_dir_all(&root).unwrap();
        let path = root.join("history.db");
        std::fs::create_dir(&path).unwrap(); // An unavailable store must be retried later.
        let mut journal = Journal::new(path.clone());
        assert!(journal.try_write(|_| Ok(())).is_err());
        std::fs::remove_dir(&path).unwrap();
        let row = DrivingDay {
            day: "2026-11-01".into(),
            distance_km: Some(12.0),
            ..DrivingDay::default()
        };
        journal
            .try_write(|store| store.vehicle_day("synthetic", &row))
            .unwrap();
        assert!(
            journal
                .try_write(|store| store.vehicle_day("synthetic", &DrivingDay::default()))
                .is_err()
        );
        journal
            .try_write(|store| {
                assert!(
                    store
                        .vehicle_report("synthetic", "2026-11-01", "2026-11-01")?
                        .to_string()
                        .contains("12.0")
                );
                Ok(())
            })
            .unwrap();
        drop(journal);
        std::fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn composition_rejects_unknown_wrong_unit_future_and_stale_samples() {
        use moli_core::{Sample, Value};
        let mut sample = Sample {
            ts: 1000,
            value: Value::from_json(&serde_json::json!(12.5)),
        };
        assert_eq!(
            composition_value(&sample, Some(&Unit::Celsius), &Unit::Celsius, 1100, 100),
            Some(12.5)
        );
        for (unit, now) in [
            (None, 1100),
            (Some(Unit::KiloWattHour), 1100),
            (Some(Unit::Celsius), 999),
            (Some(Unit::Celsius), 1101),
        ] {
            assert!(composition_value(&sample, unit.as_ref(), &Unit::Celsius, now, 100).is_none());
        }
        sample.value = Value::Null;
        assert!(
            composition_value(&sample, Some(&Unit::Celsius), &Unit::Celsius, 1100, 100).is_none()
        );
    }
}
