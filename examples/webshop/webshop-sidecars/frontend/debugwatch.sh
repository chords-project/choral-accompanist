#!/bin/sh
set -e

sigint_handler()
{
    echo "Received SIGINT signal"
    kill -15 $PID
    wait $PID
    exit
}

trap sigint_handler SIGINT

while true; do
    echo "--- Starting service frontend.jar --- "
    java -jar frontend.jar &
    PID=$!
    inotifywait -e create -e modify . 
    echo "--- Reloading service frontend.jar (pid $PID) --- "
    wait 5
    kill -15 $PID || true
    wait $PID || true
done