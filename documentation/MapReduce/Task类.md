# Task类

```
private String jobFile;                         // 作业配置文件
private String user;                            // 作业用户
private TaskAttemptID taskId;                   // unique, includes job id
private int partition;                          // 该task在当前job中的索引号
TaskStatus taskStatus;                          // 当前task的状态
protected JobStatus.State jobRunStateForCleanup;
protected boolean jobCleanup = false;			// 标志位
protected boolean jobSetup = false;				// 标志位
protected boolean taskCleanup = false;			// 标志位

private SortedRanges skipRanges = new SortedRanges();
private boolean skipping = false;
private boolean writeSkipRecs = true;

//currently processing record start index
private volatile long currentRecStartIndex;
private Iterator<Long> currentRecIndexIterator =
    skipRanges.skipRangeIterator();

private ResourceCalculatorProcessTree pTree;
private long initCpuCumulativeTime = 0;

protected JobConf conf;
protected MapOutputFile mapOutputFile;
protected LocalDirAllocator lDirAlloc;
private final static int MAX_RETRIES = 10;
protected JobContext jobContext;
protected TaskAttemptContext taskContext;
protected org.apache.hadoop.mapreduce.OutputFormat<?,?> outputFormat;
protected org.apache.hadoop.mapreduce.OutputCommitter committer;


```

## TaskReporter类

## run()函数

```
public abstract void run(JobConf job, TaskUmbilicalProtocol umbilical)
    throws IOException, ClassNotFoundException, InterruptedException;
```

## reportNextRecordRange()函数

## startReporter()函数

## updateResourceCounters()函数

## GcTimeUpdater类

## FileSystemStatisticUpdater类

```
private Map<String, FileSystemStatisticUpdater> statisticUpdaters =
    new HashMap<String, FileSystemStatisticUpdater>();
```

## write()函数 / readFields()函数

## initialize()函数

MapTask.run() / ReduceTask.run()将调用initialize()函数

```
// 1
protected JobContext jobContext;
protected TaskAttemptContext taskContext;
···
jobContext = new JobContextImpl(job, id, reporter);
taskContext = new TaskAttemptContextImpl(job, taskId, reporter);

// 2
protected org.apache.hadoop.mapreduce.OutputFormat<?,?> outputFormat;
protected org.apache.hadoop.mapreduce.OutputCommitter committer;
···
outputFormat = ReflectionUtils.newInstance(taskContext.getOutputFormatClass(), job);
committer = outputFormat.getOutputCommitter(taskContext);


```

```
public void initialize(JobConf job, JobID id,
                           Reporter reporter,
                           boolean useNewApi) throws IOException,
                                       ClassNotFoundException, InterruptedException {
    jobContext = new JobContextImpl(job, id, reporter);
    taskContext = new TaskAttemptContextImpl(job, taskId, reporter);

    if (getState() == TaskStatus.State.UNASSIGNED) {
        setState(TaskStatus.State.RUNNING);
    }

	// 设置使用新api
    if (useNewApi) {
        outputFormat =
            ReflectionUtils.newInstance(taskContext.getOutputFormatClass(), job);
        committer = outputFormat.getOutputCommitter(taskContext);
    } else {
        committer = conf.getOutputCommitter();
    }

	// 为FileOutputFormat设置输出路径
	// FileOutputFormat.setWorkOutputPath()为job设置临时输出目录
    Path outputPath = FileOutputFormat.getOutputPath(conf);
    if (outputPath != null) {
        if ((committer instanceof FileOutputCommitter)) {
            FileOutputFormat.setWorkOutputPath(conf,
                                               ((FileOutputCommitter)committer).getTaskAttemptPath(taskContext));
        } else {
            FileOutputFormat.setWorkOutputPath(conf, outputPath);
        }
    }

	// 为task设置输出的临时目录
	// 若committer的具体实现类型为FileOutputCommitter extends OutputCommitter，setupTask()函数不执行任何操作。
	// task的临时文件目录在task输出数据时才会被创建。 
    committer.setupTask(taskContext);

	// 创建ResourceCalculatorProcessTree
    Class<? extends ResourceCalculatorProcessTree> clazz =
        conf.getClass(MRConfig.RESOURCE_CALCULATOR_PROCESS_TREE,
                      null, ResourceCalculatorProcessTree.class);
    pTree = ResourceCalculatorProcessTree
            .getResourceCalculatorProcessTree(System.getenv().get("JVM_PID"), clazz, conf);
    if (pTree != null) {
        pTree.updateProcessTree();
        initCpuCumulativeTime = pTree.getCumulativeCpuTime();
    }
}
```

## normalizeStatus()函数

## updateCounters()函数

## done()函数

```
// 1
TaskUmbilicalProtocol umbilical
LocalJobRunner.Job implement TaskUmbilicalProtocol

```

```
public void done(TaskUmbilicalProtocol umbilical,
                     TaskReporter reporter
                ) throws IOException, InterruptedException {

	// 更新计数器 TO-DO
    updateCounters();

	// 根据task的不同类型及OutputCommitter是否需要commit，commitRequired检查task是否由数据需要commit
    boolean commitRequired = isCommitRequired();

    if (commitRequired) {
        int retries = MAX_RETRIES;

		// 设置task的状态为COMMIT_PENDING
        setState(TaskStatus.State.COMMIT_PENDING);

        while (true) {
            try {
				// task已经完成，正在等待commit
                umbilical.commitPending(taskId, taskStatus);
                break;
            } catch (InterruptedException ie) {
                // ignore
            } catch (IOException ie) {
                if (--retries == 0) {
                    System.exit(67);
                }
            }
        }
		// commit
        commit(umbilical, reporter, committer);
    }
    taskDone.set(true);
    reporter.stopCommunicationThread();
    // Make sure we send at least one set of counter increments. It's
    // ok to call updateCounters() in this thread after comm thread stopped.
    updateCounters();
    sendLastUpdate(umbilical);
    //signal the tasktracker that we are done
    sendDone(umbilical);
}

```

