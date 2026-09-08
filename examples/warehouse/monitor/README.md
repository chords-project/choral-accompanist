# Monitor Service

The purpose of the monitor service is to expose metrics about the state of the service mailboxes.
The service inspects the contents of the sidecar databases and reports back Prometheous metrics on things like active
choreography sessions and size of the inbox and outbox queues.