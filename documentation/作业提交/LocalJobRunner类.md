# LocalJobRunner类

```
private FileSystem fs;
private HashMap<JobID, Job> jobs = new HashMap<JobID, Job>();
private JobConf conf;
private AtomicInteger map_tasks = new AtomicInteger(0);		// map task的数量
private AtomicInteger reduce_tasks = new AtomicInteger(0);	// reduce task的数量
final Random rand = new Random();
```

## Job类

```
// The job directory on the system: JobClient places job configurations here.
// This is analogous to JobTracker's system directory.
private Path systemJobDir;
private Path systemJobFile;

// The job directory for the task.  Analagous to a task's job directory.
private Path localJobDir;
private Path localJobFile;

private JobID id;
private JobConf job;

private int numMapTasks;
private int numReduceTasks;
private float [] partialMapProgress;
private float [] partialReduceProgress;
private Counters [] mapCounters;
private Counters [] reduceCounters;

private JobStatus status;
private List<TaskAttemptID> mapIds = Collections.synchronizedList(
        new ArrayList<TaskAttemptID>());

private JobProfile profile;
private FileSystem localFs;
boolean killed = false;
```

1. Job.waitForCompletion() => Job.submit() => JobSubmitter.submitJobInternal()
2. submitClient.submitJob() => LocalJobRunner.submitJob()
3. Job job = new Job(...) => this.start() => job.run()

### Job构造函数

1. 初始化Job成员变量
2. 初始化LocalJobRunner分布式缓存管理LocalDistributedCacheManager对象
3. 将配置文件写入本地路径，初始化JobConf对象
4. 为当前Job封装类加载器
5. 初始化JobProfile对象和JobStatus对象
6. 将新提交的Job加入jobs中
7. 启动Job线程

```
// TO-DO
JobProfile
JobStatus
```

```
public Job(JobID jobid, String jobSubmitDir) throws IOException {
	// 初始化Job的成员变量
    this.systemJobDir = new Path(jobSubmitDir);
	// 配置文件路径
    this.systemJobFile = new Path(systemJobDir, "job.xml");
    this.id = jobid;
    JobConf conf = new JobConf(systemJobFile);
    this.localFs = FileSystem.getLocal(conf);
    String user = UserGroupInformation.getCurrentUser().getShortUserName();
    this.localJobDir = localFs.makeQualified(new Path(
                           new Path(conf.getLocalPath(jobDir), user), jobid.toString()));
    this.localJobFile = new Path(this.localJobDir, id + ".xml");

    // Manage the distributed cache.  If there are files to be copied,
    // this will trigger localFile to be re-written again.
    localDistributedCacheManager = new LocalDistributedCacheManager();
    localDistributedCacheManager.setup(conf);

    // Write out configuration file.  Instead of copying it from
    // systemJobFile, we re-write it, since setup(), above, may have
    // updated it.
    OutputStream out = localFs.create(localJobFile);
    try {
        conf.writeXml(out);
    } finally {
        out.close();
    }
    this.job = new JobConf(localJobFile);

    // Job (the current object) is a Thread, so we wrap its class loader.
    if (localDistributedCacheManager.hasLocalClasspaths()) {
        setContextClassLoader(localDistributedCacheManager.makeClassLoader(
                                  getContextClassLoader()));
    }

    profile = new JobProfile(job.getUser(), id, systemJobFile.toString(),
                             "http://localhost:8080/", job.getJobName());
    status = new JobStatus(id, 0.0f, 0.0f, JobStatus.RUNNING,
                           profile.getUser(), profile.getJobName(), profile.getJobFile(),
                           profile.getURL().toString());

    jobs.put(id, this);

    this.start();
}
```

### Job.run()函数

```
// 1
JobContextImpl implements JobContext	// JobContextImpl中主要保存了配置文件JobConf对象

// 2
OutputCommitter
createOutputCommitter()
outputCommitter.setupJob()
outputCommitter.commitJob()
outputCommitter.abortJob()

// 3
TaskSplitMetaInfo[] taskSplitMetaInfos	// 保存分片信息

// 4
JobEndNotifier.localRunnerNotification(job, status);
```

1. 获取JobID和JobContexr。
2. 创建FileOutputCommitter对象。
3. 获取map task和reduce task的数量。
4. 创建map task线程池并开始运行map task。
5. 创建reduce task线程池并开始运行reduce task。
6. 提交job结果。

```
public void run() {
    JobID jobId = profile.getJobID();
    JobContext jContext = new JobContextImpl(job, jobId);

    org.apache.hadoop.mapreduce.OutputCommitter outputCommitter = null;
    try {
        outputCommitter = createOutputCommitter(conf.getUseNewMapper(), jobId, conf);
    } catch (Exception e) {
        return;
    }

    try {
		// 记录输入内容的分片信息
        TaskSplitMetaInfo[] taskSplitMetaInfos =
            SplitMetaInfoReader.readSplitMetaInfo(jobId, localFs, conf, systemJobDir);
	
		// 获得reduce task的数量
        int numReduceTasks = job.getNumReduceTasks();
        outputCommitter.setupJob(jContext);
        status.setSetupProgress(1.0f);
		
		// 
        Map<TaskAttemptID, MapOutputFile> mapOutputFiles =
            Collections.synchronizedMap(new HashMap<TaskAttemptID, MapOutputFile>());

		// 根据分片的数量，获得map task的数量
        List<RunnableWithThrowable> mapRunnables = getMapTaskRunnables(
                    taskSplitMetaInfos, jobId, mapOutputFiles);

		// 初始化计数器
        initCounters(mapRunnables.size(), numReduceTasks);
		// 创建map task的线程池
        ExecutorService mapService = createMapExecutor();
		// 开始运行map task
        runTasks(mapRunnables, mapService, "map");

        try {
            if (numReduceTasks > 0) {
				// 如果job包含了reduce task，为每一个reduce task创建ReduceTaskRunnable对象
                List<RunnableWithThrowable> reduceRunnables = getReduceTaskRunnables(
                            jobId, mapOutputFiles);
				// 创建reduce task的线程池
                ExecutorService reduceService = createReduceExecutor();
				// 开始运行reduce task
                runTasks(reduceRunnables, reduceService, "reduce");
            }
        } finally {
            for (MapOutputFile output : mapOutputFiles.values()) {
                output.removeAll();
            }
        }

		// job完成，提交job结果，删除临时文件目录
        outputCommitter.commitJob(jContext);
        status.setCleanupProgress(1.0f);

        if (killed) {
            this.status.setRunState(JobStatus.KILLED);
        } else {
            this.status.setRunState(JobStatus.SUCCEEDED);
        }

        JobEndNotifier.localRunnerNotification(job, status);
    } catch (Throwable t) {
        try {
            outputCommitter.abortJob(jContext,
                                     org.apache.hadoop.mapreduce.JobStatus.State.FAILED);
        } catch (IOException ioe) {
        }
        status.setCleanupProgress(1.0f);
        if (killed) {
            this.status.setRunState(JobStatus.KILLED);
        } else {
            this.status.setRunState(JobStatus.FAILED);
        }
        LOG.warn(id, t);

        JobEndNotifier.localRunnerNotification(job, status);
    } finally {
        try {
            fs.delete(systemJobFile.getParent(), true);  		// delete submit dir
            localFs.delete(localJobFile, true);              	// delete local copy
            // Cleanup distributed cache
            localDistributedCacheManager.close();
        } catch (IOException e) {
            LOG.warn("Error cleaning up "+id+": "+e);
        }
    }
}
```

### Job.RunnableWithThrowable类

RunnableWithThrowable类有MapTaskRunnable类和ReduceTaskRunnable类两个派生类，用于封装map task和reduce task对象，并提交到线程池中进行执行。

```
protected abstract class RunnableWithThrowable implements Runnable {
    public volatile Throwable storedException;
```

### Job.getMapTaskRunnables()函数

从TaskSplitMetaInfo[]数组中获得所有的map task的MapTaskRunnable对象，并将map task添加到List中返回。

```
protected List<RunnableWithThrowable> getMapTaskRunnables(
    TaskSplitMetaInfo [] taskInfo, JobID jobId,
    Map<TaskAttemptID, MapOutputFile> mapOutputFiles) {

    int numTasks = 0;
    ArrayList<RunnableWithThrowable> list = new ArrayList<RunnableWithThrowable>();
    for (TaskSplitMetaInfo task : taskInfo) {
        list.add(new MapTaskRunnable(task, numTasks++, jobId, mapOutputFiles));
    }

    return list;
}
```
### Job.getReduceTaskRunnable()函数

构造numReduceTasks个reduce task的ReduceTaskRunnable对象。

```
protected List<RunnableWithThrowable> getReduceTaskRunnables(
    JobID jobId, Map<TaskAttemptID, MapOutputFile> mapOutputFiles) {

    int taskId = 0;
    ArrayList<RunnableWithThrowable> list =
        new ArrayList<RunnableWithThrowable>();
    for (int i = 0; i < this.numReduceTasks; i++) {
        list.add(new ReduceTaskRunnable(taskId++, jobId, mapOutputFiles));
    }

    return list;
}
```

### Job.MapTaskRunnable类

```
private final int taskId;
private final TaskSplitMetaInfo info;
private final JobID jobId;
private final JobConf localConf;
```

#### Job.MapTaskRunnable.run()函数

```
// 1
setupChildMapredLocalDirs(map, localConf);

// 2
MapOutputFile mapOutput = new MROutputFiles();
=======
Job.MapTaskRunnable继承了Job.RunnableWithThrowable，而Job.RunnableWithThrowable又继承了Runnable，因此Job.MapTaskRunnable需要实现run()方法，而run()方法也是Job.MapTaskRunnable类核心。

```
private final int taskId;				// task id
private final TaskSplitMetaInfo info;	// task的信息
private final JobID jobId;				// task对应的job信息
private final JobConf localConf;		// task对应job的配置文件
private final Map<TaskAttemptID, MapOutputFile> mapOutputFiles;		// 传递给reducer，告知其获取mapper输出的位置
```

```
public void run() {
    try {
        // 获取map task的TaskAttemptID
		TaskAttemptID mapId = new TaskAttemptID(new TaskID(jobId, TaskType.MAP, taskId), 0);
        // 启动task
		// 将map的TaskAttemptID添加到mapIds中存储
        mapIds.add(mapId);
		
		// 创建MapTask对象
        MapTask map = new MapTask(systemJobFile.toString(), mapId, taskId, info.getSplitIndex(), 1);

        map.setUser(UserGroupInformation.getCurrentUser().getShortUserName());
		// setupChildMapredLocalDirs()根据jobId、taskId等获得当前task的本地目录地址
		// 设置配置文件中mapreduce.cluster.local.dir的值
        setupChildMapredLocalDirs(map, localConf);

		// MROutputFiles对象控制map和reduce临时存储位置
        MapOutputFile mapOutput = new MROutputFiles();
        mapOutput.setConf(localConf);
        mapOutputFiles.put(mapId, mapOutput);

        map.setJobFile(localJobFile.toString());
        localConf.setUser(map.getUser());
        map.localizeConfiguration(localConf);
        map.setConf(localConf);
        try {
            map_tasks.getAndIncrement();
            myMetrics.launchMap(mapId);
            map.run(localConf, Job.this);
            myMetrics.completeMap(mapId);
        } finally {
            map_tasks.getAndDecrement();
        }

        LOG.info("Finishing task: " + mapId);
    } catch (Throwable e) {
        this.storedException = e;
    }
}
```

### Job.ReduceTaskRunnable类

### Job.getMapTaskRunnables()和Job.getReduceTaskRunnables()函数

创建封装了map task和reduce task的MapTaskRunnables和ReduceTaskRunnables对象列表，用于提交至对应的线程池中执行task任务。

```
protected List<RunnableWithThrowable> getMapTaskRunnables(
    TaskSplitMetaInfo [] taskInfo, JobID jobId,
    Map<TaskAttemptID, MapOutputFile> mapOutputFiles) {

    int numTasks = 0;
    ArrayList<RunnableWithThrowable> list =
        new ArrayList<RunnableWithThrowable>();
    for (TaskSplitMetaInfo task : taskInfo) {
        list.add(new MapTaskRunnable(task, numTasks++, jobId,
                                     mapOutputFiles));
    }
    return list;
}

protected List<RunnableWithThrowable> getReduceTaskRunnables(
    JobID jobId, Map<TaskAttemptID, MapOutputFile> mapOutputFiles) {

    int taskId = 0;
    ArrayList<RunnableWithThrowable> list =
        new ArrayList<RunnableWithThrowable>();
    for (int i = 0; i < this.numReduceTasks; i++) {
        list.add(new ReduceTaskRunnable(taskId++, jobId, mapOutputFiles));
    }
    return list;
}
```

## submitJob()函数

submitJob()函数由JobSubmitter.submitJobInternal()函数调用，负责提交job。

```
public org.apache.hadoop.mapreduce.JobStatus submitJob(
    org.apache.hadoop.mapreduce.JobID jobid, String jobSubmitDir,
    Credentials credentials) throws IOException {
	
	// 创建一个实现了TaskUmbilicalProtocol接口的Job对象
    Job job = new Job(JobID.downgrade(jobid), jobSubmitDir);
	// JobConf job.job 安全设置
    job.job.setCredentials(credentials);
    return job.status;
}
```