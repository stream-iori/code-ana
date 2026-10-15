# Complex Mini Spring Shop

This deterministic Maven reactor exercises Agent navigation across API, application,
inventory, persistence, notification, and batch modules.

The phrase **assured checkout** means the static order-creation route beginning at
`POST /api/orders` and ending after persistence and notification dispatch. Static
Spring wiring identifies configured candidates, not the bean selected by a running
deployment.

The optional `acceptance` profile adds an immutable behavioral test module used by
the Jury adapter after an Agent implements the restricted-SKU rule.

