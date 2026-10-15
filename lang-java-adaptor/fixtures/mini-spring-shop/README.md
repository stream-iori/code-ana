# mini-spring-shop fixture

自建的多模块 Spring Boot 2.7.18 项目,使用 JDK 8 编译,作为 anatomist 的 L2/L3 测试 fixture。

## 模块

| 模块 | 内容 | 覆盖的 anatomist 测试点 |
|------|------|---------------------|
| `domain` | Order/OrderItem/OrderStatus(enum)/CreateOrderRequest/OrderResult/OrderCreatedEvent | nodes.module 字段、ENUM/ENUM_CONSTANT、JavaBean 字段 |
| `service` | BaseService/OrderService/OrderValidator/PriceCalculator/OrderRepository/InMemoryOrderRepository/AuditedOrderRepository/OrderEventPublisher | INHERITS、OVERRIDES、IMPLEMENTS、递归实现类、CALLS(INSTANCE/STATIC/INTERFACE)、READS/WRITES、Lambda、匿名类、方法重载、@Service/@Transactional/@Autowired |
| `api` | ShopApplication/OrderController + JUnit 5 自测 | @RestController、@PostMapping、@PathVariable、@RequestBody、跨模块依赖 |

## OrderService.createOrder 调用链(D3/D4 金标准)

```
OrderController.create
  → OrderService.createOrder(CreateOrderRequest)
    → java.util.Objects.requireNonNull          [STATIC, external]
    → OrderValidator.validate                   [INSTANCE]
    → PriceCalculator.calculate                 [INSTANCE]
    → OrderService.applyDiscount                [INSTANCE, OVERRIDES BaseService.applyDiscount]
    → Order.<init>                              [CONSTRUCTOR]
    → Order.setStatus                           [INSTANCE, WRITES Order.status]
    → OrderRepository.save                      [INTERFACE]
    → OrderCreatedEvent.<init>                  [CONSTRUCTOR]
    → OrderEventPublisher.publish               [INSTANCE]
```

## Repository 类型链

```
OrderRepository
  ├─ InMemoryOrderRepository        [direct IMPLEMENTS]
  │   └─ AuditedOrderRepository     [recursive INHERITS]
```

该链路用于覆盖 `resolve OrderRepository | runtime-implementations`。

## 构建 / 测试

需要 **JDK 8** 编译,**Maven 3.6+**。

```bash
# 切到 JDK 8(本仓库 .sdkmanrc 是 JDK 25,需临时覆盖)
sdk use java 8-temurin
mvn -f fixtures/mini-spring-shop clean test
```

## anatomist 索引时如何使用

```bash
# 索引(Phase 1 完成后)
anatomist index fixtures/mini-spring-shop \
  --java-version 8 \
  --project-source "domain/src/main/java:service/src/main/java:api/src/main/java"

# 查询直接调用和静态派发候选
anatomist resolve \
  "com.example.shop.service.OrderService#createOrder(com.example.shop.domain.dto.CreateOrderRequest)" \
  --kind callable --exact --unique |
  anatomist calls |
  anatomist dispatch
```

## 注意事项

- 不引入 Lombok——保持源码"裸"以便 JavaParser+SymbolSolver 解析结果可预期
- 不使用 JPA/JDBC——`InMemoryOrderRepository` 替代,避免引入数据库依赖
- `OrderService` 故意塞 Lambda(`countExpensiveItems`)和匿名类(`cleanupTask`)以覆盖 LAMBDA/ANONYMOUS_CLASS 节点
- `PriceCalculator.calculate` 使用方法引用(`OrderItem::getPrice`)覆盖 METHOD_REF
