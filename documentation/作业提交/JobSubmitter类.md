# JobSubmitter

```
private static final String SHUFFLE_KEYGEN_ALGORITHM = "HmacSHA1";
private static final int SHUFFLE_KEY_LENGTH = 64;

// 文件系统
private FileSystem jtFs;

// 客户端通信协议ClientProtocol实例，用于与集群交互，完成作业提交，作业状态查询扥功能
private ClientProtocol submitClient;

// 提交作业的主机名
private String submitHostName;

// 提交作业的主机地址
private String submitHostAddress;
```

## 构造函数

```
JobSubmitter(FileSystem submitFs, ClientProtocol submitClient)
	throws IOException {
    this.submitClient = submitClient;
    this.jtFs = submitFs;
}
```

## submitJobInternal()函数

submitJobInternal()函数用于向集群提交作业。

- checkSpecs()函数
- addMRFrameworkToDistributedCache()函数
- JobSubmissionFiles.getStagingDir()函数 / JobSubmissionFiles类
- TokenCache类
- JobResourceUploader类
- JobSplitWriter类

```
JobStatus submitJobInternal(Job job, Cluster cluster)
    throws ClassNotFoundException, InterruptedException, IOException {

    //validate the jobs output specs
	// checkSpecs()函数校验作业输出路径是否已经配置与是否已经存在
    checkSpecs(job);

    Configuration conf = job.getConfiguration();

	// addMRFrameworkToDistributedCache()函数添加MapReduce应用框架路径到分布式缓存中
    addMRFrameworkToDistributedCache(conf);

	// getStagingDir()函数获取作业执行时相关资源的存放路径，默认路径为/tmp/hadoop-yarn/staging/用户名/.staging
    Path jobStagingArea = JobSubmissionFiles.getStagingDir(cluster, conf);
    //configure the command line options correctly on the submitting dfs
    InetAddress ip = InetAddress.getLocalHost();
    if (ip != null) {
        submitHostAddress = ip.getHostAddress();
        submitHostName = ip.getHostName();
        conf.set(MRJobConfig.JOB_SUBMITHOST,submitHostName);
        conf.set(MRJobConfig.JOB_SUBMITHOSTADDR,submitHostAddress);
    }

	// 获取新的作业ID，并设置job的JobID
    JobID jobId = submitClient.getNewJobID();
    job.setJobID(jobId);

	// 构造提交作业路径，jobStagingArea + /jobID
    Path submitJobDir = new Path(jobStagingArea, jobId.toString());

    JobStatus status = null;
    try {
		// 设置作业参数
        conf.set(MRJobConfig.USER_NAME,
                 UserGroupInformation.getCurrentUser().getShortUserName());
        conf.set("hadoop.http.filter.initializers",
                 "org.apache.hadoop.yarn.server.webproxy.amfilter.AmFilterInitializer");
        conf.set(MRJobConfig.MAPREDUCE_JOB_DIR, submitJobDir.toString());

        // get delegation token for the dir
        TokenCache.obtainTokensForNamenodes(job.getCredentials(),
                                            new Path[] { submitJobDir }, conf);

        populateTokenCache(conf, job.getCredentials());

        // generate a secret to authenticate shuffle transfers
        if (TokenCache.getShuffleSecretKey(job.getCredentials()) == null) {
            KeyGenerator keyGen;
            try {

                int keyLen = CryptoUtils.isShuffleEncrypted(conf)
                             ? conf.getInt(MRJobConfig.MR_ENCRYPTED_INTERMEDIATE_DATA_KEY_SIZE_BITS,
                                           MRJobConfig.DEFAULT_MR_ENCRYPTED_INTERMEDIATE_DATA_KEY_SIZE_BITS)
                             : SHUFFLE_KEY_LENGTH;
                keyGen = KeyGenerator.getInstance(SHUFFLE_KEYGEN_ALGORITHM);
                keyGen.init(keyLen);
            } catch (NoSuchAlgorithmException e) {
                throw new IOException("Error generating shuffle secret key", e);
            }
            SecretKey shuffleKey = keyGen.generateKey();
            TokenCache.setShuffleSecretKey(shuffleKey.getEncoded(),
                                           job.getCredentials());
        }

        copyAndConfigureFiles(job, submitJobDir);

		// 获取配置文件路径
        Path submitJobFile = JobSubmissionFiles.getJobConfPath(submitJobDir);

        // Create the splits for the job
        int maps = writeSplits(job, submitJobDir);
        conf.setInt(MRJobConfig.NUM_MAPS, maps);

        // write "queue admins of the queue to which job is being submitted"
        // to job file.
        String queue = conf.get(MRJobConfig.QUEUE_NAME,
                                JobConf.DEFAULT_QUEUE_NAME);
        AccessControlList acl = submitClient.getQueueAdmins(queue);
        conf.set(toFullPropertyName(queue,
                                    QueueACL.ADMINISTER_JOBS.getAclName()), acl.getAclString());

        // removing jobtoken referrals before copying the jobconf to HDFS
        // as the tasks don't need this setting, actually they may break
        // because of it if present as the referral will point to a
        // different job.
        TokenCache.cleanUpTokenReferral(conf);

        if (conf.getBoolean(
                MRJobConfig.JOB_TOKEN_TRACKING_IDS_ENABLED,
                MRJobConfig.DEFAULT_JOB_TOKEN_TRACKING_IDS_ENABLED)) {
            // Add HDFS tracking ids
            ArrayList<String> trackingIds = new ArrayList<String>();
            for (Token<? extends TokenIdentifier> t :
                 job.getCredentials().getAllTokens()) {
                trackingIds.add(t.decodeIdentifier().getTrackingId());
            }
            conf.setStrings(MRJobConfig.JOB_TOKEN_TRACKING_IDS,
                            trackingIds.toArray(new String[trackingIds.size()]));
        }

        // Set reservation info if it exists
        ReservationId reservationId = job.getReservationId();
        if (reservationId != null) {
            conf.set(MRJobConfig.RESERVATION_ID, reservationId.toString());
        }

        // Write job file to submit dir
        writeConf(conf, submitJobFile);

        //
        // Now, actually submit the job (using the submit name)
        //
        printTokens(jobId, job.getCredentials());
        status = submitClient.submitJob(
                     jobId, submitJobDir.toString(), job.getCredentials());
        if (status != null) {
            return status;
        } else {
            throw new IOException("Could not launch job");
        }
    } finally {
        if (status == null) {
            LOG.info("Cleaning up the staging area " + submitJobDir);
            if (jtFs != null && submitJobDir != null)
                jtFs.delete(submitJobDir, true);

        }
    }
}

```