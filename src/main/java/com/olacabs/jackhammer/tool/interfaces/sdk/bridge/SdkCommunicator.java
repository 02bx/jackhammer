package com.olacabs.jackhammer.tool.interfaces.sdk.bridge;


import com.codahale.metrics.annotation.ExceptionMetered;
import com.codahale.metrics.annotation.Metered;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.olacabs.jackhammer.common.Constants;
import com.olacabs.jackhammer.configuration.WebSocketsConfiguration;
import com.olacabs.jackhammer.db.ToolInstanceDAO;
import com.olacabs.jackhammer.models.Scan;
import com.olacabs.jackhammer.models.Tool;
import com.olacabs.jackhammer.models.ToolInstance;
import com.olacabs.jackhammer.tool.interfaces.request.ScanRequest;
import com.olacabs.jackhammer.tool.interfaces.request.ScanRequestEncoder;
import com.olacabs.jackhammer.tool.interfaces.response.ScanResponse;
import com.olacabs.jackhammer.tool.interfaces.response.ScanResponseDecoder;
import com.olacabs.jackhammer.tool.interfaces.response.ToolResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jetty.websocket.api.WebSocketException;

import javax.websocket.*;
import javax.websocket.server.ServerEndpoint;
import java.net.SocketTimeoutException;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Metered
@ExceptionMetered

@ServerEndpoint(value = Constants.WEB_SOCKET_API_END_POINT, encoders = {ScanRequestEncoder.class}, decoders = {ScanResponseDecoder.class},
        configurator = WebSocketsConfiguration.class)
public class SdkCommunicator {

    public static Set<Session> clients = new HashSet<Session>();
    private static Map<String, String> toolInstances = new HashMap();

//    private static Session session;

    @Inject
    ScanRequest scanRequest;

    @Inject
    ScanResponse scanResponse;

    @Inject
    ToolResponse toolResponse;

    @Inject
    @Named(Constants.TOOL_INSTANCE_DAO)
    ToolInstanceDAO toolInstanceDAO;

    @OnOpen
    public void onOpen(Session session) {
        clients.add(session);
        session.getContainer().setDefaultMaxSessionIdleTimeout(0);
        log.info("Connected ... " + session.getId());
    }

    @OnMessage(maxMessageSize = 20 * 1024 * 1024)
    public String onClientResponse(String message, Session session) {
        if (!toolResponse.isToolResponse(message)) {
            long toolInstanceId = Long.parseLong(toolInstances.get(session.getId()));
//            toolResponse.decreaseRunningScans(toolInstanceId);
            scanResponse.saveScanResponse(message, toolInstanceId);
        } else {
            log.info("received response from client....." + message);
            ToolInstance toolInstance = toolResponse.addToolInstance(message, session);
            toolInstances.put(session.getId(), String.valueOf(toolInstance.getId()));
        }
        return message;
    }

    @OnClose
    public void onClose(Session session, CloseReason closeReason) {
        String sessionId = session.getId();
        try {
            long toolInstanceId = Long.getLong(toolInstances.get(sessionId));
            toolResponse.deleteToolInstance(toolInstanceId);
            clients.remove(session);
            log.info(String.format("Session %s closed because of %s", session.getId(), closeReason));
        } catch (NullPointerException ne) {
            toolResponse.deleteToolInstanceBySession(sessionId);
            log.info("sessionId got killed {} {}", sessionId);
            clients.remove(session);
            log.error("Session was not exists", ne);
        }
    }


    @OnError
    public void onError(Throwable t, Session session) {
        try {
            if (t instanceof SocketTimeoutException) {
                log.error("Error while reading  web socket with session id", session.getId());
                long toolInstanceId = Long.getLong(toolInstances.get(session.getId()));
                toolResponse.deleteToolInstance(toolInstanceId);
                clients.remove(session);
            }
        } catch (NullPointerException ne) {
            log.error("Error while reading  web socket with session id", session.getId());
        }
    }

    public void sendScanRequest(Scan scan) {
        try {
//            synchronized (clients) {
            List<Tool> toolList = scan.getToolList();
            int pickedTools = 0;
            for (Tool tool : toolList) {
                List<ToolInstance> toolInstanceList = toolInstanceDAO.getByToolId(tool.getId());
                log.info("no of tool instances are {} {}", toolInstanceList.size());
                for (ToolInstance toolInstance : toolInstanceList) {
                    Session session = getToolInstanceSession(toolInstance);
                    if (session != null && scan.getScanPlatforms().contains(toolInstance.getPlatform().toLowerCase()) && toolInstance.getCurrentRunningScans() < toolInstance.getMaxAllowedScans()) {
                        try {
                            if (scan.getApkTempFile() != null) {
                                setApkFileName(scan);
                            }
                            session.getBasicRemote().sendObject(scan);
                            pickedTools += 1;
                            toolResponse.updateScanToolStatus(scan.getId(), tool.getId(), toolInstance.getId());
                            break;
                        } catch (WebSocketException we) {
                            log.info("WebSocketException while sending scan to remote tool");
                        } catch (NullPointerException nl) {
                            log.info("NullPointerException while sending scan to remote tool");
                        }
                    }
                }
            }
            if (pickedTools > 0) scanRequest.changeScanStatus(scan);
//            }
        } catch (Exception e) {
            log.error("Error while picking session tool", e);
        } catch (Throwable e) {
            log.error("Error while picking session tool", e);
            e.printStackTrace();
        }
    }

    private Session getToolInstanceSession(ToolInstance toolInstance) {
        Session session = null;
        try {
            log.info("Total sessions => {} {}", clients.size());
            for (Session clientSession : clients) {
                if (StringUtils.equals(clientSession.getId(), toolInstance.getSessionId())) {
                    session = clientSession;
                }
            }
        } catch (Throwable e) {
            log.error("Error while picking session tool", e);
            e.printStackTrace();
        }
        return session;
    }

    private void setApkFileName(Scan scan) {
        int index = scan.getApkTempFile().lastIndexOf(Constants.URL_SEPARATOR);
        String fileName = scan.getApkTempFile().substring(index + 1);
        scan.setApkTempFile(fileName);
    }

    private void verifyToolInstanceScanCount(final long toolInstanceId, final Timestamp updatedAt) {
        ToolInstance toolInstance = toolInstanceDAO.get(toolInstanceId);
        if (updatedAt != toolInstance.getUpdatedAt() || toolInstance.getCurrentRunningScans() == 0  ) return;
        log.info("Running scan count not updated....{} {}", toolInstanceId);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        Runnable task = new Runnable() {
            public void run() {
                try {
                    while (true) {
                        ToolInstance toolInstance = toolInstanceDAO.get(toolInstanceId);
                        if (updatedAt == toolInstance.getUpdatedAt() && toolInstance.getCurrentRunningScans()!= 0) {
                            log.info("Record got locked,re-trying to update running scans count...{}..{}", toolInstanceId);
                            toolResponse.decreaseRunningScans(toolInstanceId);
                        } else {
                            break;
                        }
                    }
                } catch (Exception e) {
                    log.error("Error while re-trying to update running scans count..{}..{}", toolInstanceId);
                }
            }
        };
        int delay = 1;
        scheduler.schedule(task, delay, TimeUnit.SECONDS);
        scheduler.shutdown();
    }
}
