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

Job执行时，Task结果输出到Output路径下的_temporary目录中，以TaskAttemptId命名的子目录下。只有当Task成功时，相应的输出才会被提交到Output路径下。只有当整个Job都成功时，才会在Output路径下生成_SUCCESS文件，表明Output路径下的输出信息是正确完整的。若不包含_SUCCESS文件，Output路径下的信息依然正确，但不一定是完整的，可能只包含部分输出结果。

- setupJob － mkdir ${mapred.output.dir}/_temporary
- commitJob － touch ${mapred.output.dir}/_SUCCESS && rm -r ${mapred.output.dir}/_temporary
- abortJob － rm -r ${mapred.output.dir}/_temporary
- setupTask － <nothing>
- needsTaskCommit － test -d ${mapred.output.dir}/_temporary/_${TaskAttemptID}
- commitTask － mv ${mapred.output.dir}/_temporary/_${TaskAttemptID}/* ${mapred.output.dir}/
- abortTask － rm -r ${mapred.output.dir}/_temporary/_${TaskAttemptID}

```
// outputPath是job最终输出的路径，也可以看做应用的attempt的最终提交路径
private Path outputPath = null;
private Path workPath = null;
```

## setupJob()函数

为该job下所有task创建临时工作目录。

```
public void setupJob(JobContext context) throws IOException {
    if (hasOutputPath()) {
		// jobAttemptPath = outputPath + "_temporary"
        Path jobAttemptPath = getJobAttemptPath(context);
        FileSystem fs = jobAttemptPath.getFileSystem(
                            context.getConfiguration());
		// 为jobAttempPath创建文件夹
        if (!fs.mkdirs(jobAttemptPath)) {
            LOG.error("Mkdirs failed to create " + jobAttemptPath);
        }
    } else {
        LOG.warn("Output Path is null in setupJob()");
    }
}
```

## commitJob()函数

job完成后，将task的结果提交到最终输出目录，并删除setupJob()中创建的临时目录。

getAllCommittedTaskPaths()返回所有需提交的task状态。

mergePaths()函数将两个路径合并。mergePaths(fs, from, to)中，所有from路径中的内容会被移动到to路径中，from路径中的文件会覆盖in中的同名文件。

```
public void commitJob(JobContext context) throws IOException {
    if (hasOutputPath()) {
		// 获取task提交的最终路径
        Path finalOutput = getOutputPath();
        FileSystem fs = finalOutput.getFileSystem(context.getConfiguration());
		// 将所有需要提交的task移动到最终路径中
        for(FileStatus stat: getAllCommittedTaskPaths(context)) {
            mergePaths(fs, stat, finalOutput);
        }

        // 删除临时文件夹
        cleanupJob(context);

        // True if the job requires output.dir marked on successful job.
        // Note that by default it is set to true.
		// 创建job成功后的SUCCESS文件
        if (context.getConfiguration().getBoolean(SUCCESSFUL_JOB_OUTPUT_DIR_MARKER, true)) {
            Path markerPath = new Path(outputPath, SUCCEEDED_FILE_NAME);
            fs.create(markerPath).close();
        }
    } else {
        LOG.warn("Output Path is null in commitJob()");
    }
}
```

## cleanupJob()函数

cleanupJob()函数删除临时文件目录，即以"_temporary"为后缀的目录下的文件。

```
public void cleanupJob(JobContext context) throws IOException {
	if (hasOutputPath()) {
        Path pendingJobAttemptsPath = getPendingJobAttemptsPath();
        FileSystem fs = pendingJobAttemptsPath
                        .getFileSystem(context.getConfiguration());
        fs.delete(pendingJobAttemptsPath, true);
    } else {
    }
}
```

## commitTask()函数

将临时文件夹中的task结果移动到最终结果输出路径中。

```
public void commitTask(TaskAttemptContext context, Path taskAttemptPath)
	throws IOException {
	// 获取当前task attempt的id
    TaskAttemptID attemptId = context.getTaskAttemptID();
    if (hasOutputPath()) {
		// 返回当前任务进度
        context.progress();
		// 获取task attempt文件路径
        if(taskAttemptPath == null) {
            taskAttemptPath = getTaskAttemptPath(context);
        }

		// 获得task输出的最终路径
        Path committedTaskPath = getCommittedTaskPath(context);
        FileSystem fs = taskAttemptPath.getFileSystem(context.getConfiguration());

		// 将taskAttemptPath重命名为committedTaskPath
        if (fs.exists(taskAttemptPath)) {
            if(fs.exists(committedTaskPath)) {
                if(!fs.delete(committedTaskPath, true)) {
                    throw new IOException("Could not delete " + committedTaskPath);
                }
            }
            if(!fs.rename(taskAttemptPath, committedTaskPath)) {
                throw new IOException("Could not rename " + taskAttemptPath + " to "
                                      + committedTaskPath);
            }
        } else {     
        }
    } else {
    }
}
```

## abortTask()函数

删除task attempt路径下的文件

```
public void abortTask(TaskAttemptContext context, Path taskAttemptPath) throws IOException {
    if (hasOutputPath()) {
        context.progress();
        if(taskAttemptPath == null) {
            taskAttemptPath = getTaskAttemptPath(context);
        }
        FileSystem fs = taskAttemptPath.getFileSystem(context.getConfiguration());
        if(!fs.delete(taskAttemptPath, true)) {
            LOG.warn("Could not delete "+taskAttemptPath);
        }
    } else {
        LOG.warn("Output Path is null in abortTask()");
    }
}
```