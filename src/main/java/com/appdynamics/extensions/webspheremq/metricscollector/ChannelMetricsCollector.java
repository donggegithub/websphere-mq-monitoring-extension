package com.appdynamics.extensions.webspheremq.metricscollector;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;

import com.appdynamics.extensions.util.metrics.MetricConstants;
import com.appdynamics.extensions.util.metrics.MetricOverride;
import com.appdynamics.extensions.webspheremq.common.Util;
import com.appdynamics.extensions.webspheremq.config.ChannelExcludeFilters;
import com.appdynamics.extensions.webspheremq.config.ChannelIncludeFilters;
import com.appdynamics.extensions.webspheremq.config.QueueManager;
import com.appdynamics.extensions.webspheremq.config.WMQMetricOverride;
import com.ibm.mq.MQException;
import com.ibm.mq.constants.CMQC;
import com.ibm.mq.constants.CMQCFC;
import com.ibm.mq.constants.MQConstants;
import com.ibm.mq.pcf.PCFException;
import com.ibm.mq.pcf.PCFMessage;
import com.ibm.mq.pcf.PCFMessageAgent;
import com.singularity.ee.agent.systemagent.api.AManagedMonitor;
import com.singularity.ee.agent.systemagent.api.exception.TaskExecutionException;

public class ChannelMetricsCollector extends MetricsCollector {
	
	public static final Logger logger = Logger.getLogger(ChannelMetricsCollector.class);
	private final String artifact = "Channels";
	private List<String> channelList;

	private String[] chStatusTextArr = { "", "MQCHS_BINDING", "MQCHS_STARTING", "MQCHS_RUNNING", "MQCHS_STOPPING", "MQCHS_RETRYING", "MQCHS_STOPPED", "MQCHS_REQUESTING", "MQCHS_PAUSED", "", "", "",
			"", "MQCHS_INITIALIZING" };
	private String[] channelTypes = { "", "SDR", "SVR", "RCVR", "RQSTR", "", "CLTCN", "SVRCN", "CLUSRCVR", "CLUSSDR", "" };

	public ChannelMetricsCollector(Map<String, ? extends MetricOverride> metricsToReport, AManagedMonitor monitor, PCFMessageAgent agent, QueueManager queueManager, String metricPrefix) {
		this.metricsToReport = metricsToReport;
		this.monitor = monitor;
		this.agent = agent;
		this.metricPrefix = metricPrefix;
		this.queueManager = queueManager;
	}

	public void processFilter() throws TaskExecutionException {
		List<String> allChannels = getChannelList(queueManager);

		// First evaluate include filters and then exclude filters
		ChannelIncludeFilters includeFilters = this.queueManager.getChannelIncludeFilters();
		List<String> includedChannels = evalIncludeFilter(includeFilters.getType(), allChannels, includeFilters.getValues());

		ChannelExcludeFilters excludeFilters = this.queueManager.getChannelExcludeFilters();
		channelList = evalExcludeFilter(excludeFilters.getType(), includedChannels, excludeFilters.getValues());

	}

	public String getAtrifact() {
		return artifact;
	}

	public void publishMetrics() throws TaskExecutionException {
		if (channelList == null || channelList.isEmpty()) {
			logger.debug("channel List empty");
			return;
		}
		for (String channelName : channelList) {
			publishChannelMetrics(channelName.trim());
		}
	}

	private void publishChannelMetrics(String channelName) {
		PCFMessage request;
		PCFMessage[] response;
		int[] attrs = new int[getMetricsToReport().size() + 2];
		attrs[0] = CMQCFC.MQCACH_CHANNEL_NAME;
		attrs[1] = CMQCFC.MQCACH_CONNECTION_NAME;
		Iterator<String> overrideItr = getMetricsToReport().keySet().iterator();
		for (int count = 2; overrideItr.hasNext() && count < attrs.length; count++) {
			String metrickey = overrideItr.next();
			WMQMetricOverride wmqOverride = (WMQMetricOverride) getMetricsToReport().get(metrickey);
			attrs[count] = wmqOverride.getConstantValue();
		}
		logger.debug("Attributes being sent along PCF agent request to query channel metrics: " + Arrays.toString(attrs));
		request = new PCFMessage(CMQCFC.MQCMD_INQUIRE_CHANNEL_STATUS);
		request.addParameter(CMQCFC.MQCACH_CHANNEL_NAME, channelName);
		request.addParameter(CMQCFC.MQIACH_CHANNEL_INSTANCE_TYPE, CMQC.MQOT_CURRENT_CHANNEL);
		request.addParameter(CMQCFC.MQIACH_CHANNEL_INSTANCE_ATTRS, attrs);
		try {
			logger.debug("sending PCF agent request to query channel metrics");
			response = agent.send(request);
			logger.debug("PCF agent request sent, response received");
			for (int i = 0; i < response.length; i++) {
				Iterator<String> itr = getMetricsToReport().keySet().iterator();
				while (itr.hasNext()) {
					String metrickey = itr.next();
					WMQMetricOverride wmqOverride = (WMQMetricOverride) getMetricsToReport().get(metrickey);
					int metricVal = response[i].getIntParameterValue(wmqOverride.getConstantValue());
					if (logger.isDebugEnabled()) {
						logger.debug("Metric: " + wmqOverride.getMetricKey() + "=" + metricVal);
					}
					StringBuilder metricNameBuilder = new StringBuilder(this.metricPrefix);
					metricNameBuilder.append(queueManager.getName());
					metricNameBuilder.append(MetricConstants.METRICS_SEPARATOR);
					metricNameBuilder.append(getAtrifact());//Channels in this case
					metricNameBuilder.append(MetricConstants.METRICS_SEPARATOR);
					metricNameBuilder.append(channelName);
					metricNameBuilder.append(MetricConstants.METRICS_SEPARATOR);
					metricNameBuilder.append(wmqOverride.getMetricKey());
					String metricName = metricNameBuilder.toString();
					BigInteger bigVal = toBigInteger(metricVal, getMultiplier(wmqOverride));
					printMetric(metricName, String.valueOf(bigVal.intValue()), wmqOverride.getAggregator(), wmqOverride.getTimeRollup(), wmqOverride.getClusterRollup(), monitor);
				}
			}
		} catch (PCFException pcfe) {
			String errorMsg = "";
			if (pcfe.getReason() == MQConstants.MQRCCF_CHL_STATUS_NOT_FOUND) {
				errorMsg = "Could not collect channel information as channel is stopped or inactive, check config.yaml: Reason '3065'\n";
				errorMsg += "If the channel type is MQCHT_RECEIVER, MQCHT_SVRCONN or MQCHT_CLUSRCVR, then the only action is to enable the channel, not start it.";
				logger.debug(errorMsg);
			} else if (pcfe.getReason() == MQConstants.MQRC_SELECTOR_ERROR) {
				logger.debug("Invalid metrics passed while collecting channel metrics, check config.yaml: Reason '2067'");
			}
		} catch (Exception e) {
			logger.debug("Unexpected Error occoured while collecting metrics for channel "+channelName+" "+e);
		}
	}

	public Map<String, ? extends MetricOverride> getMetricsToReport() {
		return this.metricsToReport;
	}

	private List<String> getChannelList(QueueManager queueManager) throws TaskExecutionException {
		
		List<String> channels = new ArrayList<String>();
		try {

			String padding = null;
			char[] space = new char[64];

			Arrays.fill(space, 0, space.length, ' ');

			padding = new String(space);

			// Create the PCF message type for the channel names inquire.
			PCFMessage pcfCmd = new PCFMessage(MQConstants.MQCMD_INQUIRE_CHANNEL_NAMES);

			// Add the inquire rules.
			// Queue name = wildcard.
			pcfCmd.addParameter(MQConstants.MQCACH_CHANNEL_NAME, "*");

			// Channel type = ALL.
			pcfCmd.addParameter(MQConstants.MQIACH_CHANNEL_TYPE, MQConstants.MQCHT_ALL);

			PCFMessage[] pcfResponse = agent.send(pcfCmd);

			// For each returned message, extract the message from the array and
			// display the
			// required information.
			logger.debug("+-----+------------------------------------------------+----------+");
			logger.debug("|Index|                  Channel Name                  |   Type   |");
			logger.debug("+-----+------------------------------------------------+----------+");

			// The Channel information is held in some array element of the
			// response object (the
			// contents of the response object is defined in the documentation).
			for (int responseNumber = 0; responseNumber < pcfResponse.length; responseNumber++) {
				String[] names = (String[]) pcfResponse[responseNumber].getParameterValue(MQConstants.MQCACH_CHANNEL_NAMES);

				// There might not be any names, so test this first before
				// attempting to parse the object.
				if (names != null) {
					int[] types = (int[]) pcfResponse[responseNumber].getParameterValue(MQConstants.MQIACH_CHANNEL_TYPES);

					

					for (int index = 0; index < names.length; index++) {

						channels.add(names[index].trim());

						logger.debug("|" + (index + padding).substring(0, 5) + "|" + (names[index] + padding).substring(0, 48) + "|" + (channelTypes[types[index]] + padding).substring(0, 10) + "|");
					}

					logger.debug("+-----+------------------------------------------------+----------+");
					break;
				}
			}

			return channels;

		} catch (PCFException ex) {
			// TODO added logging to see if we may be getting errors that don't
			// show up
			// Change this to debug or remove after testing
			logger.warn("Error getting channel list", ex);
			throw new TaskExecutionException(ex);
		} catch (IOException e) {
			throw new TaskExecutionException(e);
		} catch (MQException e) {
			// TODO Auto-generated catch block
			throw new TaskExecutionException(e);
		}
	}

}