-- Durable, one-minute end-to-end throughput for a selected benchmark run.
SELECT date_trunc('minute', completed_at) AS time_bin, COUNT(DISTINCT session_id)::double precision / 60 AS completed_per_second
FROM session_states
WHERE run_id = CAST(:run_id AS UUID) AND session_state = 'completed'
GROUP BY time_bin ORDER BY time_bin;

-- Export the exact completion record included in a run bundle.
SELECT session_id, choreography, run_id, started_at, completed_at, attempt_count, restart_count
FROM session_states
WHERE run_id = CAST(:run_id AS UUID) AND session_state = 'completed'
ORDER BY completed_at;
