package com.nike.mm.facade.impl

import com.nike.mm.business.internal.IJobHistoryBusiness
import com.nike.mm.business.internal.IMeasureMentorJobsConfigBusiness
import com.nike.mm.business.internal.IMeasureMentorRunBusiness
import com.nike.mm.business.plugins.IMeasureMentorBusiness
import com.nike.mm.dto.MeasureMentorConfigValidationDto
import com.nike.mm.dto.RunnableMeasureMentorBusinessAndConfigDto
import com.nike.mm.entity.JobHistory
import com.nike.mm.facade.IMeasureMentorRunFacade
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service

import java.text.SimpleDateFormat

@Service
class MeasureMentorRunFacade implements IMeasureMentorRunFacade {

    @Autowired
    Set<IMeasureMentorBusiness> measureMentorBusinesses

    @Autowired
    IMeasureMentorJobsConfigBusiness measureMentorConfigBusiness

    @Autowired
    IMeasureMentorRunBusiness measureMentorRunBusiness

    @Autowired
    IJobHistoryBusiness jobHistoryBusiness

    @Override
    @Async
    void runJobId(String jobid) {
        def startDate = new Date();
        if (this.measureMentorRunBusiness.isJobRunning(jobid)) {
            this.jobHistoryBusiness.save([jobid : jobid, startDate: startDate, endDate: new Date(), success: 'false',
                                          status: "jobrunning", comments: ('Job already running for the jobid:' +
                    jobid)] as JobHistory);
            //TODO Make this polling... shouldn't be hard...
            throw new RuntimeException("Job already running: " + jobid);
        }
        try {
            this.measureMentorRunBusiness.startJob(jobid);
            def configDto = this.measureMentorConfigBusiness.findById(jobid);

            MeasureMentorConfigValidationDto mmcvDto = this.validateTheConfigFilesAndPlugins(configDto.config);
            mmcvDto.configId = configDto.id;

            JobHistory previousJh = this.jobHistoryBusiness.findLastSuccessfulJobRanForJobid(jobid);
            if (previousJh) {
                mmcvDto.previousJobId = previousJh.id;
            }
            if (!mmcvDto.errors.isEmpty()) {
                this.jobHistoryBusiness.save([jobid: jobid, startDate: startDate, endDate: new Date(), success:
                        'false', status            : "error", comments: mmcvDto.getMessageAsString()] as JobHistory);
            } else {
                //TODO We need to get agreeget data as well as success monicers from this.
                this.runMmbs(getLastRunDateOrDefault(previousJh), mmcvDto);
                //TODO: Error handling.
                this.jobHistoryBusiness.save([jobid: jobid, startDate: startDate, endDate: new Date(), success:
                        false, status              : "success", comments: ("Success for jobid: " + jobid)] as
                        JobHistory);
            }
        } finally {
            this.measureMentorRunBusiness.stopJob(jobid);
        }
    }

    private Date getLastRunDateOrDefault(JobHistory previousJh) {
        Date date = new SimpleDateFormat("dd/MM/yyyy").parse("01/01/1901");
        if (previousJh) {
            date = previousJh.endDate;
        }
        return date;
    }

    private void runMmbs(Date lastRunDate, MeasureMentorConfigValidationDto mmcvDto) {
        //TODO run in parallel.
        //TODO error tracking.
        for (RunnableMeasureMentorBusinessAndConfigDto dto : mmcvDto.runnableMmbs) {
            dto.measureMentorBusiness.updateData(dto.config, lastRunDate)
        }
    }

    private MeasureMentorConfigValidationDto validateTheConfigFilesAndPlugins(def configs) {
        MeasureMentorConfigValidationDto mmcvDto = new MeasureMentorConfigValidationDto();
        configs.each { config ->
            IMeasureMentorBusiness mmb = this.findByType(config.type)
            if (mmb == null) {
                mmcvDto.errors.add("No measure mentor configured for: $config.type");
            } else if (!mmb.validateConfig(config)) {
                mmcvDto.errors.add("Config not valid config for: $config.type");
            } else {
                mmcvDto.runnableMmbs.add([measureMentorBusiness: mmb, config: config] as
                        RunnableMeasureMentorBusinessAndConfigDto)
            }
        }
        return mmcvDto;
    }

    private IMeasureMentorBusiness findByType(final String type) {
        for (IMeasureMentorBusiness mmb : this.measureMentorBusinesses) {
            if (mmb.type() == type) {
                return mmb;
            }
        }
        return null;
    }
}
