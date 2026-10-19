import unittest
from simulation import Mirror, Sample


class SimulationTests(unittest.TestCase):
    def setUp(self):
        # Test values only, not boiler commissioning parameters.
        self.m = Mirror(max_age=20, min_on=30, min_off=30)
        self.m.reconcile_virtual(False, 0)

    def step(self, demand=True, now=40, **kw):
        return self.m.step(Sample(demand, kw.pop('observed_at', now),
                                  kw.pop('received_at', now), **kw), now)['reason']

    def test_on_off_and_dwell(self):
        self.assertEqual(self.step(), 'virtual-on')
        self.assertEqual(self.step(False, 41), 'dwell')
        self.assertTrue(self.m.target)
        self.assertEqual(self.step(False, 70), 'virtual-off')
        self.assertEqual(self.step(True, 71), 'dwell')
        self.assertEqual(self.step(True, 100), 'virtual-on')

    def test_off_remains_off(self):
        self.assertEqual(self.step(False), 'unchanged')
        self.assertFalse(self.m.target)

    def test_fresh_http_does_not_refresh_old_observation(self):
        self.assertEqual(self.step(observed_at=1), 'unknown-or-stale-time')
        self.assertFalse(self.m.target)

    def test_missing_future_and_nonfinite_time(self):
        for stamp in (None, 41, float('nan'), float('inf'), True):
            with self.subTest(stamp=stamp):
                self.assertEqual(self.step(observed_at=stamp), 'unknown-or-stale-time')

    def test_connectivity_loss_does_not_force_off(self):
        self.step()
        self.assertEqual(self.step(False, 80, connected=False), 'unavailable')
        self.assertTrue(self.m.target)
        self.assertEqual(self.step(False, 90, reachable=False), 'unavailable')
        self.assertTrue(self.m.target)

    def test_missing_or_percentage_is_not_boolean(self):
        for demand in (None, 0, 1, 100, 'true'):
            self.assertEqual(self.step(demand), 'unknown-demand')

    def test_contradiction(self):
        self.assertEqual(self.step(contradictory=True), 'contradiction')
        self.assertFalse(self.m.target)

    def test_restart_requires_reconciliation_and_new_dwell(self):
        self.step()
        self.m = Mirror(max_age=20, min_on=30, min_off=30)
        self.assertEqual(self.step(now=80), 'restart-reconcile')
        self.assertIsNone(self.m.target)
        self.m.reconcile_virtual(False, 81)
        self.assertEqual(self.step(now=82), 'dwell')
        self.assertEqual(self.step(now=111), 'virtual-on')

    def test_recovery_uses_current_demand(self):
        self.assertEqual(self.step(connected=False), 'unavailable')
        self.assertEqual(self.step(False, 50), 'unchanged')
        self.assertEqual(self.step(True, 60), 'virtual-on')

    def test_clock_regression_requires_reconciliation(self):
        self.step()
        self.assertEqual(self.step(now=30), 'clock-regression-reconcile')
        self.assertIsNone(self.m.target)

    def test_journal_records_each_decision(self):
        self.step()
        self.step(False, 41)
        self.assertEqual([r['reason'] for r in self.m.journal],
                         ['reconciled', 'virtual-on', 'dwell'])

    def test_inconsistent_observation_receipt(self):
        self.assertEqual(self.step(observed_at=40, received_at=39), 'inconsistent-time')


if __name__ == '__main__':
    unittest.main()
