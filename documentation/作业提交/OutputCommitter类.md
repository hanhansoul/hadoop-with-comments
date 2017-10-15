# OutputCommitter类

http://www.cnblogs.com/noures/archive/2012/07/13/2589767.html

OutputCommitter类用于控制job的输出环境。

MapReduce框架依赖OutputCommitter实现以下功能：
1. 在初始化时配置job，如在job初始化时为job创建临时输出目录。
2. 在job完成后清除job，如在job完成后清除临时输出目录。
3. 设置task的临时输出。
4. 检查task是否需要提交操作
5. 提交task的输出。
6. 舍弃提交task。

```
// setupJob()在job初始化时配置输出路径
public abstract void setupJob(JobContext jobContext) throws IOException;

// 在job完成后清除job的输出文件
public void cleanupJob(JobContext jobContext) throws IOException { }

// 在job完成后提交job的输出
public void commitJob(JobContext jobContext) throws IOException {
    cleanupJob(jobContext);
}

// 为task设置输出路径
public abstract void setupTask(TaskAttemptContext taskContext) throws IOException;

// 将task的临时输出提交到最终输出位置
public abstract void commitTask(TaskAttemptContext taskContext) throws IOException;

// 恢复task输出
public void recoverTask(TaskAttemptContext taskContext) throws IOException {}
```

# FileOutputCommitter类

FileOutputCommiter继承自OutputCommitter。

```
// outputPath是job最终输出的路径，也可以看做应用attempt提交的路径
private Path outputPath = null;
private Path workPath = null;
```