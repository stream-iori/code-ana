# Order lifecycle

The business term **assured checkout** covers this source-backed path:

1. `POST /api/orders` accepts a `CreateOrderRequest`.
2. The application service performs admission and risk checks.
3. Inventory is reserved before the order repository saves the order.
4. The stored order is published and dispatched to the configured notification channels.

The application service requests `strictRiskPolicy` and `auditedOrderRepository` by
qualifier. Those qualifiers are static configuration evidence; profiles, overrides,
and runtime conditions can still change the actual deployment.

