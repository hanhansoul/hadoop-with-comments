# chapter3 Hadoop数据处理

## Spark

### Spark概述

#### DAG模型

MapReduce只包含两种处理阶段：map和reduce。MapReduce框架下，只能通过将map和reduce任务进行顺序排列构建并完成应用工程。这种复杂的链式任务模型即DAG模型(directed acyclic graphs，有向无环图)。

相比于MapReduce，Spark实现了引擎根据应用的逻辑，自己创建复杂的链式步骤，而不需要从外部添加DAG模型到应用中去。Spark允许开发者在同一个job中开发复杂算法和数据处理管道，同时框架会以一个整体优化job，提高性能。

#### Spark组件

- Driver
- DAG调度器
-  

