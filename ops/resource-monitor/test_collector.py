import unittest
from collector import cpu_sample, memory_sample, size_bytes, history_sample, containers_sample


class CollectorTest(unittest.TestCase):
    def test_cpu_requires_two_samples_and_excludes_guest_double_count(self):
        value, cores, state = cpu_sample('cpu 10 0 10 80 0 0 0 0 10 0\ncpu0 1\ncpu1 1', {})
        self.assertIsNone(value)
        self.assertEqual(cores, 2)
        value, _, _ = cpu_sample('cpu 20 0 20 160 0 0 0 0 20 0', state)
        self.assertEqual(value, 20)

    def test_counter_reset_does_not_invent_cpu_usage(self):
        self.assertIsNone(cpu_sample('cpu 1 0 1 8', {'total': 100, 'idle': 80})[0])

    def test_memory_uses_available_not_free(self):
        value = memory_sample('MemTotal: 1000 kB\nMemAvailable: 400 kB\nMemFree: 1 kB\nSwapTotal: 200 kB\nSwapFree: 150 kB')
        self.assertEqual(value['memoryUsedBytes'], 600 * 1024)
        self.assertEqual(value['memoryPercent'], 60)
        self.assertEqual(value['swapUsedBytes'], 50 * 1024)

    def test_memory_missing_available_does_not_fall_back_to_zero(self):
        with self.assertRaises(KeyError):
            memory_sample('MemTotal: 100 kB\nMemFree: 1 kB')

    def test_docker_sizes_support_iec_and_si(self):
        self.assertEqual(size_bytes('1.5MiB'), 1572864)
        self.assertEqual(size_bytes('1.5MB'), 1500000)
        self.assertEqual(size_bytes('0B'), 0)
        with self.assertRaises(ValueError):
            size_bytes('unknown')

    def test_history_is_bounded_and_rejects_future_samples(self):
        old = [dict(sampledAt=f'2026-10-22T00:{i//60:02}:{i%60:02}Z') for i in range(90)]
        rows = history_sample(old, dict(cpuPercent=None, memoryPercent=70), '2026-10-22T00:01:20Z')
        self.assertEqual(len(rows), 60)
        self.assertEqual(rows[-1]['sampledAt'], '2026-10-22T00:01:20Z')
        self.assertIsNone(rows[-1]['cpuPercent'])
        self.assertTrue(all(row['sampledAt'] < rows[-1]['sampledAt'] for row in rows[:-1]))

    def test_containers_only_query_allowed_projects_and_keep_stopped_unknown(self):
        calls = []
        def run(*args):
            calls.append(args)
            if args[0] == 'ps':
                return 'aaaaaaaaaaaa\n' if 'label=com.docker.compose.project=glucopilot' in args else ''
            if args[0] == 'inspect':
                return '{"id":"aaaaaaaaaaaa0000","name":"/glucopilot-api-1","service":"api","state":"exited","limit":0}'
            raise AssertionError('Stopped containers should not be queried with stats')
        rows = containers_sample(run)
        self.assertEqual(rows[0]['name'], 'glucopilot-api-1')
        self.assertIsNone(rows[0]['memoryUsedBytes'])
        self.assertIsNone(rows[0]['memoryLimitBytes'])
        self.assertEqual(len(calls), 3)


if __name__ == '__main__':
    unittest.main()
