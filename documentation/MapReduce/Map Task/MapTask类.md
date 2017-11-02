# MapTask类

## TrackedRecordReader类

TrackedRecordReader类实现了RecordReader接口，用于封装用户的RecordReader对象，更新计数器与task进度。

## SkippingRecordReader类

SkippingRecordReader类继承了TrackedRecordReader类，用于跳过在之前的task attempt中失败了的task范围。

```
// 1. TaskUmbilicalProtocol
private class Job extends Thread implements TaskUmbilicalProtocol
public class TaskAttemptListenerImpl extends CompositeService implements TaskUmbilicalProtocol, TaskAttemptListener
```

```
private SkipRangeIterator skipIt;
private SequenceFile.Writer skipWriter;
private boolean toWriteSkipRecs;
private TaskUmbilicalProtocol umbilical;
private Counters.Counter skipRecCounter;
private long recIndex = -1;
```

## NewTrackingRecordReader类

与TrackedRecordReader类相似，不同的是NewTrackingRecordReader类用于新API。

## run()函数

run()
initialize()
runJobCleanupTask() / runJobSetupTask() / runTaskCleanupTask()
runNewMapper() / runOldMapper()
done()

1. 根据reducer的数量设置Progress各阶段的比例。
2. 创建Taskreporter对象。
3. 调用MapTask.initialize()函数初始化。
4. 判断该task是否为jobCleanup/jobSetup/taskCleanup任务，并执行对应的内容。
5. 如果都不是以上任务，则调用runNewMapper()/runOldMapper()执行map操作。
6. 调用done()。

```
// MapTask类的run()函数实现了Task类的run()函数
public abstract void run(JobConf job, TaskUmbilicalProtocol umbilical)
    throws IOException, ClassNotFoundException, InterruptedException;
```

```
public void run(final JobConf job, final TaskUmbilicalProtocol umbilical)
    throws IOException, ClassNotFoundException, InterruptedException {
    this.umbilical = umbilical;
	// 根据是否包含reducer设置Progress对象
    if (isMapTask()) {
        if (conf.getNumReduceTasks() == 0) {
            mapPhase = getProgress().addPhase("map", 1.0f);
        } else {
            mapPhase = getProgress().addPhase("map", 0.667f);
            sortPhase  = getProgress().addPhase("sort", 0.333f);
        }
    }
    TaskReporter reporter = startReporter(umbilical);

    boolean useNewApi = job.getUseNewMapper();
    initialize(job, getJobID(), reporter, useNewApi);

    // 判断是否为CleanupJobTask
    if (jobCleanup) {
        runJobCleanupTask(umbilical, reporter);
        return;
    }
    if (jobSetup) {
        runJobSetupTask(umbilical, reporter);
        return;
    }
    if (taskCleanup) {
        runTaskCleanupTask(umbilical, reporter);
        return;
    }

    if (useNewApi) {
        runNewMapper(job, splitMetaInfo, umbilical, reporter);
    } else {
        runOldMapper(job, splitMetaInfo, umbilical, reporter);
    }
    done(umbilical, reporter);
}
```

## Task.initialize()函数

见Task类。

## Task.done()函数

见Task类。

## runNewMapper()函数

见runNewMapper函数。

## runOldMapper()函数

与runNewMapper()函数类似，但使用旧式API。

```
private <INKEY,INVALUE,OUTKEY,OUTVALUE>
void runOldMapper(final JobConf job,
                  final TaskSplitIndex splitIndex,
                  final TaskUmbilicalProtocol umbilical,
                  TaskReporter reporter
                 ) throws IOException, InterruptedException,
    ClassNotFoundException {
    InputSplit inputSplit = getSplitDetails(new Path(splitIndex.getSplitLocation()),
                                            splitIndex.getStartOffset());

    updateJobWithSplit(job, inputSplit);
    reporter.setInputSplit(inputSplit);

    RecordReader<INKEY,INVALUE> in = isSkipping() ?
                                     new SkippingRecordReader<INKEY,INVALUE>(umbilical, reporter, job) :
                                     new TrackedRecordReader<INKEY,INVALUE>(reporter, job);
    job.setBoolean(JobContext.SKIP_RECORDS, isSkipping());


    int numReduceTasks = conf.getNumReduceTasks();
    LOG.info("numReduceTasks: " + numReduceTasks);
    MapOutputCollector<OUTKEY, OUTVALUE> collector = null;
    if (numReduceTasks > 0) {
        collector = createSortingCollector(job, reporter);
    } else {
        collector = new DirectMapOutputCollector<OUTKEY, OUTVALUE>();
        MapOutputCollector.Context context =
            new MapOutputCollector.Context(this, job, reporter);
        collector.init(context);
    }
    MapRunnable<INKEY,INVALUE,OUTKEY,OUTVALUE> runner =
        ReflectionUtils.newInstance(job.getMapRunnerClass(), job);

    try {
        runner.run(in, new OldOutputCollector(collector, conf), reporter);
        mapPhase.complete();
        // start the sort phase only if there are reducers
        if (numReduceTasks > 0) {
            setPhase(TaskStatus.Phase.SORT);
        }
        statusUpdate(umbilical);
        collector.flush();

        in.close();
        in = null;

        collector.close();
        collector = null;
    } finally {
        closeQuietly(in);
        closeQuietly(collector);
    }
}
```