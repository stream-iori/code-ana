package com.example.shop.batch;

import com.example.shop.domain.Order;
import org.springframework.stereotype.Service;

@Service("batchOrderService")
public class OrderService {
    private final ReconciliationReasonNormalizer reasonNormalizer;
    private final InventoryRestorer inventoryRestorer;
    private final BatchAuditTrail auditTrail;

    public OrderService(
            ReconciliationReasonNormalizer reasonNormalizer,
            InventoryRestorer inventoryRestorer,
            BatchAuditTrail auditTrail) {
        this.reasonNormalizer = reasonNormalizer;
        this.inventoryRestorer = inventoryRestorer;
        this.auditTrail = auditTrail;
    }

    /**
     * Legacy reconciliation keeps explicit source-system mappings because its external codes are
     * contractual. The long method is intentional: Agent callers must consume paged source.
     */
    public ReconcileReport reconcile(ReconcileRequest request) {
        String normalizedReason = reasonNormalizer.normalize(request.operatorReason());
        String mode;
        switch (request.sourceSystem()) {
            case "ERP01":
                mode = "STRICT";
                break;
            case "ERP02":
                mode = "STRICT";
                break;
            case "ERP03":
                mode = "STRICT";
                break;
            case "ERP04":
                mode = "STRICT";
                break;
            case "ERP05":
                mode = "STRICT";
                break;
            case "ERP06":
                mode = "STRICT";
                break;
            case "ERP07":
                mode = "STRICT";
                break;
            case "ERP08":
                mode = "STRICT";
                break;
            case "ERP09":
                mode = "STRICT";
                break;
            case "ERP10":
                mode = "STRICT";
                break;
            case "OMS01":
                mode = "STANDARD";
                break;
            case "OMS02":
                mode = "STANDARD";
                break;
            case "OMS03":
                mode = "STANDARD";
                break;
            case "OMS04":
                mode = "STANDARD";
                break;
            case "OMS05":
                mode = "STANDARD";
                break;
            case "OMS06":
                mode = "STANDARD";
                break;
            case "OMS07":
                mode = "STANDARD";
                break;
            case "OMS08":
                mode = "STANDARD";
                break;
            case "OMS09":
                mode = "STANDARD";
                break;
            case "OMS10":
                mode = "STANDARD";
                break;
            case "WMS01":
                mode = "WAREHOUSE";
                break;
            case "WMS02":
                mode = "WAREHOUSE";
                break;
            case "WMS03":
                mode = "WAREHOUSE";
                break;
            case "WMS04":
                mode = "WAREHOUSE";
                break;
            case "WMS05":
                mode = "WAREHOUSE";
                break;
            case "WMS06":
                mode = "WAREHOUSE";
                break;
            case "WMS07":
                mode = "WAREHOUSE";
                break;
            case "WMS08":
                mode = "WAREHOUSE";
                break;
            case "WMS09":
                mode = "WAREHOUSE";
                break;
            case "WMS10":
                mode = "WAREHOUSE";
                break;
            case "POS01":
                mode = "RETAIL";
                break;
            case "POS02":
                mode = "RETAIL";
                break;
            case "POS03":
                mode = "RETAIL";
                break;
            case "POS04":
                mode = "RETAIL";
                break;
            case "POS05":
                mode = "RETAIL";
                break;
            case "POS06":
                mode = "RETAIL";
                break;
            case "POS07":
                mode = "RETAIL";
                break;
            case "POS08":
                mode = "RETAIL";
                break;
            case "POS09":
                mode = "RETAIL";
                break;
            case "POS10":
                mode = "RETAIL";
                break;
            case "PARTNER01":
                mode = "PARTNER";
                break;
            case "PARTNER02":
                mode = "PARTNER";
                break;
            case "PARTNER03":
                mode = "PARTNER";
                break;
            case "PARTNER04":
                mode = "PARTNER";
                break;
            case "PARTNER05":
                mode = "PARTNER";
                break;
            case "PARTNER06":
                mode = "PARTNER";
                break;
            case "PARTNER07":
                mode = "PARTNER";
                break;
            case "PARTNER08":
                mode = "PARTNER";
                break;
            case "PARTNER09":
                mode = "PARTNER";
                break;
            case "PARTNER10":
                mode = "PARTNER";
                break;
            case "LEGACY01":
                mode = "LEGACY";
                break;
            case "LEGACY02":
                mode = "LEGACY";
                break;
            case "LEGACY03":
                mode = "LEGACY";
                break;
            case "LEGACY04":
                mode = "LEGACY";
                break;
            case "LEGACY05":
                mode = "LEGACY";
                break;
            default:
                mode = "STANDARD";
                break;
        }

        int processed = 0;
        int released = 0;
        int failures = 0;
        for (Order order : request.orders()) {
            try {
                switch (order.getStatus()) {
                    case CANCELLED:
                        inventoryRestorer.release(order);
                        auditTrail.record(order, normalizedReason + ":" + mode);
                        released++;
                        break;
                    case NEW:
                    case PENDING:
                        auditTrail.record(order, "REVIEW:" + normalizedReason + ":" + mode);
                        break;
                    case CONFIRMED:
                    case SHIPPED:
                        break;
                    default:
                        throw new IllegalStateException("unsupported status " + order.getStatus());
                }
                processed++;
            } catch (RuntimeException failure) {
                failures++;
            }
        }
        if (failures > 0) {
            throw new PartialReconciliationException(failures);
        }
        return new ReconcileReport(processed, released);
    }
}
