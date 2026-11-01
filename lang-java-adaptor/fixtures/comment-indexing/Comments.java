// 文件头：库存服务，只有文件定位。
package fixture.comments;

/** 库存仓库，文档属于类型。 */
class Comments {
    /** 扣减库存 stock_count；库存不足时返回 false。 */
    boolean reserve(int stockCount) {
        String literal = "// 不是注释 /* no */";
        // 检查库存，前置关联下一条语句。
        if (stockCount <= 0) return false; // 行尾库存不足。

        // 独立注释：与下一条语句有空行。

        Runnable task = () -> {
            // lambda 内部，不挂到外层 reserve。
            System.out.println("库存😀");
        };
        return true;
    }

    /* 普通块注释，不自动当作方法文档。 */
    void other() {}
}
