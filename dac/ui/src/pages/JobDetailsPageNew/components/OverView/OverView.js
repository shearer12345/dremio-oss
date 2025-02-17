/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import PropTypes from 'prop-types';
import { injectIntl } from 'react-intl';
import Immutable from 'immutable';
import timeUtils from 'utils/timeUtils';
import { getDuration } from 'utils/jobListUtils';
import { ScansForFilter } from '@app/constants/Constants';
import jobsUtils from '@app/utils/jobsUtils';
import JobDetailsErrorInfo from '../OverView/JobDetailsErrorInfo';
import { formatInputOutputRecords } from '../../Utils';
import { getFormatMessageIdForQueryType } from '../../Utils';
import JobSummary from '../Summary/Summary';
import TotalExecutionTime from '../TotalExecutionTime/TotalExecutionTime';
import HelpSection from '../../../JobPage/components/JobDetails/HelpSection';
import SQL from '../SQL/SQL';
import QueriedDataset from '../QueriedDataset/QueriedDataset';
import Scans from '../Scans/Scans';
import Reflections from '../Reflections/Reflection';
import './OverView.less';

const VIEW_ID = 'JOB_DETAILS_VIEW_ID';

const renderErrorLog = (failureInfo) => {
  return failureInfo && failureInfo.size > 0 && <JobDetailsErrorInfo failureInfo={failureInfo} />;
};

const renderCancellationLog = (cancellationInfo) => {
  return cancellationInfo && <JobDetailsErrorInfo failureInfo={cancellationInfo} />;
};

const OverView = (props) => {
  const {
    intl: {
      formatMessage
    },
    jobDetails,
    downloadJobFile,
    isContrast,
    onClick
  } = props;
  const attemptDetails = jobDetails.get('attemptDetails') || Immutable.List();
  const haveMultipleAttempts = attemptDetails.size > 1;
  const durationLabelId = haveMultipleAttempts ? 'Job.TotalDuration' : 'Job.Duration';
  const jobDuration = jobDetails.get('duration');

  const renderLastAttemptDuration = () => {
    const lastAttempt = attemptDetails && attemptDetails.last();
    const totalTimeMs = lastAttempt && lastAttempt.get('totalTime');
    return jobsUtils.formatJobDuration(totalTimeMs);
  };

  const downloadJobProfile = (viewId, jobId) => {
    downloadJobFile({
      url: `/support/${jobId}/download`,
      method: 'POST',
      viewId
    });
  };

  const jobSummaryData = [
    { label: 'Job.Status', content: jobDetails.get('jobStatus') },
    { label: 'Job.QueryType', content: formatMessage({ id: getFormatMessageIdForQueryType(jobDetails) }) },
    {
      label: 'Job.StartTime',
      content: timeUtils.formatTime(jobDetails.get('startTime'))
    },
    ...(haveMultipleAttempts ? [{
      label: 'Job.LastAttemptDuration',
      content: renderLastAttemptDuration()
    }] : []),
    {
      label: `${durationLabelId}`,
      content: `${jobsUtils.formatJobDuration(jobDuration)}`
    },
    { label: 'Job.Summary.WaitOnClient', content: `${jobsUtils.formatJobDuration(jobDetails.get('waitInClient'))}` },
    { label: 'Common.User', content: jobDetails.get('queryUser') },
    { label: 'Common.Queue', content: jobDetails.get('wlmQueue') },
    { label: 'Job.Summary.Input', content: formatInputOutputRecords(jobDetails.get('input')) },
    { label: 'Job.Summary.Output', content: formatInputOutputRecords(jobDetails.get('output')) }
  ];

  const durationDetails = jobDetails.get('durationDetails');
  const jobId = jobDetails.get('id');
  const failureInfo = jobDetails.get('failureInfo');
  const cancellationInfo = jobDetails.get('cancellationInfo');
  return (
    <div className='overview'>
      <div className='overview__leftSidePanel'>
        <JobSummary
          jobSummary={jobSummaryData}
        />
        <TotalExecutionTime
          pending={getDuration(durationDetails, 'PENDING')}
          metadataRetrival={getDuration(durationDetails, 'METADATA_RETRIEVAL')}
          planning={getDuration(durationDetails, 'PLANNING')}
          engineStart={getDuration(durationDetails, 'ENGINE_START')}
          queued={getDuration(durationDetails, 'QUEUED')}
          executionPlanning={getDuration(durationDetails, 'EXECUTION_PLANNING')}
          starting={getDuration(durationDetails, 'STARTING')}
          running={getDuration(durationDetails, 'RUNNING')}
          total={jobDuration}
        />
        <HelpSection
          jobId={jobId}
          downloadFile={() => downloadJobProfile(VIEW_ID, jobId)}
        />
      </div>
      <div className='overview__righSidePanel'>
        <div>
          {renderErrorLog(failureInfo)}
          {renderCancellationLog(cancellationInfo)}
        </div>
        <SQL
          defaultContrast={isContrast}
          onClick={onClick}
          showContrast
          sqlString={jobDetails.get('queryText')}
          title={formatMessage({ id: 'SubmittedSQL' })}
          sqlClass='overview__sqlBody' />
        <QueriedDataset queriedDataSet={jobDetails.get('queriedDatasets')} />
        <Scans scansForFilter={ScansForFilter} scans={jobDetails.get('scannedDatasets')} />
        <Reflections
          reflectionsUsed={jobDetails.get('reflectionsUsed')}
          reflectionsNotUsed={jobDetails.get('reflectionsMatched')}
          isAcceleration={jobDetails.get('accelerated')}
          isStarFlakeAccelerated={jobDetails.get('starFlakeAccelerated')}
        />
      </div>
    </div>
  );
};

OverView.propTypes = {
  intl: PropTypes.object.isRequired,
  scansForFilter: PropTypes.array,
  jobSummary: PropTypes.array,
  reflectionsUsed: PropTypes.array,
  reflectionsNotUsed: PropTypes.array,
  queriedDataSet: PropTypes.array,
  duration: PropTypes.object,
  jobDetails: PropTypes.object,
  downloadJobProfile: PropTypes.func,
  downloadJobFile: PropTypes.func,
  isContrast: PropTypes.bool,
  onClick: PropTypes.func
};
export default injectIntl(OverView);
