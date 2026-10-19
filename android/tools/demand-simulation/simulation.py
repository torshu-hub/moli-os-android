"""Offline design fixture. No networking, device adapter, or production defaults."""
from dataclasses import dataclass
import math


@dataclass(frozen=True)
class Sample:
    demand: object
    observed_at: object
    received_at: object
    connected: bool = True
    reachable: bool = True
    contradictory: bool = False


def fresh(timestamp, now, max_age):
    return (type(timestamp) in (int, float) and math.isfinite(timestamp)
            and 0 <= now - timestamp <= max_age)


class Mirror:
    """Virtual target only; unknown input never silently becomes OFF.

    Caller supplies synthetic monotonic timestamps on a common clock. A real
    connector must establish device observation age independently of HTTP receipt.
    A new process requires explicit virtual-state reconciliation before mirroring.
    """

    def __init__(self, *, max_age, min_on, min_off):
        if any(type(v) not in (int, float) or not math.isfinite(v) or v <= 0
               for v in (max_age, min_on, min_off)):
            raise ValueError('finite positive simulation intervals required')
        self.max_age, self.min_on, self.min_off = max_age, min_on, min_off
        self.target = None
        self.changed_at = None
        self.last_now = None
        self.journal = []

    def reconcile_virtual(self, state, now):
        if type(state) is not bool or not fresh(now, now, self.max_age):
            raise ValueError('explicit virtual boolean and finite time required')
        self.target, self.changed_at, self.last_now = state, now, now
        self.journal.append({'time': now, 'reason': 'reconciled', 'target': state})

    def step(self, sample, now):
        if not fresh(now, now, self.max_age):
            raise ValueError('finite time required')
        if self.last_now is not None and now < self.last_now:
            self.target = self.changed_at = None
            reason = 'clock-regression-reconcile'
        elif not sample.connected or not sample.reachable:
            reason = 'unavailable'
        elif sample.contradictory:
            reason = 'contradiction'
        elif type(sample.demand) is not bool:
            reason = 'unknown-demand'
        elif not (fresh(sample.observed_at, now, self.max_age)
                  and fresh(sample.received_at, now, self.max_age)):
            reason = 'unknown-or-stale-time'
        elif sample.observed_at > sample.received_at:
            reason = 'inconsistent-time'
        elif self.target is None:
            reason = 'restart-reconcile'
        elif sample.demand == self.target:
            reason = 'unchanged'
        elif now - self.changed_at < (self.min_on if self.target else self.min_off):
            reason = 'dwell'
        else:
            self.target, self.changed_at = sample.demand, now
            reason = 'virtual-on' if self.target else 'virtual-off'
        self.last_now = now
        record = {'time': now, 'reason': reason, 'target': self.target}
        self.journal.append(record)
        return record
