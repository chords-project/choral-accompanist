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

ARCHIVE=productcatalog.tar
SERVICE=productcatalog/bin/productcatalog

while true; do
    if [ -f $ARCHIVE ]; then
        echo "--- Extracting tar file $ARCHIVE --- "
        until tar -xvaf $ARCHIVE -C /tmp; do sleep 1; done
        echo "--- Starting service $SERVICE --- "
        /tmp/$SERVICE &
        PID=$!
    else
        echo "--- Archive does not exist, waiting for change ---"
    fi
    inotifywait .
    echo "--- Reloading service $SERVICE (pid $PID) --- "
    kill -15 $PID || true
    wait $PID || true
    rm -rf /tmp/$SERVICE
done