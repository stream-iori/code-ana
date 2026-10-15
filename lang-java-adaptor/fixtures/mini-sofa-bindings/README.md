# Internal SOFA 4 / MSGBROKER graph fixture

Hand-written API stubs model the inspected Runtime API 4.19.1 annotation members.
XML models `sofa-runtime-service:4.19.1`'s `sofa-service.xsd`: service/reference
use `interface`, `unique-id`, `ref`, and binding elements; messages use publisher,
consumer, listener, channels, and event filters. No proprietary JAR is redistributed.

The analyzers retain interface, unique ID, transport, listener and message filter
metadata as source-backed configuration nodes. They do not select a service or
listener candidate. Missing services remain unknown; message filters do not
establish delivery and never generate CALLS edges.

Enable `[index] spring_xml = true` and index with `--no-classpath`.
