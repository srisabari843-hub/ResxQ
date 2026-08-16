const express = require('express');
const http = require('http');
const { Server } = require('socket.io');
const path = require('path');
const cors = require('cors');

const app = express();
app.use(cors());
app.use(express.json());

// Serve static web app files from public folder
app.use(express.static(path.join(__dirname, 'public')));

const server = http.createServer(app);
const io = new Server(server, {
  cors: {
    origin: '*',
    methods: ['GET', 'POST', 'PATCH']
  }
});

// Connected peers registry & Persistent SOS Alerts Storage
const activePeers = new Map();
const sosAlertsMap = new Map();

// Helper to broadcast updated SOS alerts to all Rescue Team dashboards
function broadcastSosAlerts() {
  const alertsList = Array.from(sosAlertsMap.values()).sort((a, b) => b.timestamp - a.timestamp);
  io.emit('sos_alerts_update', alertsList);
}

function broadcastPeerList() {
  const peersArray = Array.from(activePeers.values());
  io.emit('peers_update', peersArray);
}

// ----------------------------------------------------
// REST API Endpoints for Gateway Sync & Rescue Team
// ----------------------------------------------------

// 1. Batch Gateway Sync Endpoint (Called by Android phones when network connects)
app.post('/api/sync-sos', (req, res) => {
  try {
    const { gatewayDeviceId, alerts } = req.body;
    if (gatewayDeviceId) {
      activePeers.set(`gateway_${gatewayDeviceId}`, {
        deviceId: gatewayDeviceId,
        displayName: `Gateway Node (${gatewayDeviceId.substring(0, 6)})`,
        clientType: 'android',
        socketId: `http_${gatewayDeviceId}`
      });
      broadcastPeerList();
    }

    let newAlertsCount = 0;
    alerts.forEach((alert) => {
      const sosId = alert.sosId || `sos_${Date.now()}_${Math.random().toString(36).substring(2, 6)}`;
      const existing = sosAlertsMap.get(sosId);

      const updatedAlert = {
        sosId: sosId,
        victimName: alert.victimName || 'Unknown Victim',
        victimDeviceId: alert.victimDeviceId || 'unknown',
        urgency: alert.urgency || 'CRITICAL',
        emergencyType: alert.emergencyType || 'Medical Emergency',
        message: alert.message || 'Help needed!',
        locationText: alert.locationText || 'Disaster Area',
        latitude: alert.latitude || null,
        longitude: alert.longitude || null,
        batteryLevel: alert.batteryLevel || 100,
        timestamp: alert.timestamp || Date.now(),
        gatewayDeviceId: gatewayDeviceId || alert.gatewayDeviceId || 'Direct Gateway',
        status: existing ? existing.status : (alert.status || 'PENDING'),
        isSyncedToRescueTeam: true
      };

      sosAlertsMap.set(sosId, updatedAlert);
      if (!existing) newAlertsCount++;

      // Trigger immediate real-time event for newly arrived SOS
      io.emit('sos_alert_new', updatedAlert);
    });

    console.log(`[⚡ GATEWAY SYNC SUCCESS] Synced ${alerts.length} alerts (${newAlertsCount} new). Total stored: ${sosAlertsMap.size}`);
    broadcastSosAlerts();

    return res.status(200).json({
      success: true,
      syncedCount: alerts.length,
      newAlertsCount: newAlertsCount
    });
  } catch (err) {
    console.error('[Sync Error]', err);
    return res.status(500).json({ error: err.message });
  }
});

// 2. GET all SOS alerts for Rescue Team Dashboard
app.get('/api/sos-alerts', (req, res) => {
  const alertsList = Array.from(sosAlertsMap.values()).sort((a, b) => b.timestamp - a.timestamp);
  res.json(alertsList);
});

// 3. PATCH Rescue Status (PENDING -> DISPATCHED -> RESCUED)
app.patch('/api/sos-alerts/:sosId/status', (req, res) => {
  const { sosId } = req.params;
  const { status } = req.body;

  const alert = sosAlertsMap.get(sosId);
  if (!alert) {
    return res.status(404).json({ error: 'SOS alert not found' });
  }

  alert.status = status;
  sosAlertsMap.set(sosId, alert);
  console.log(`[RESCUE STATUS CHANGE] Alert ${sosId} -> ${status}`);

  io.emit('sos_status_changed', { sosId, status });
  broadcastSosAlerts();

  return res.json({ success: true, alert });
});

// ----------------------------------------------------
// Socket.IO Events
// ----------------------------------------------------
io.on('connection', (socket) => {
  console.log(`[+] Client connected: ${socket.id}`);

  // Send current SOS list to newly connected Rescue Team web client
  socket.emit('sos_alerts_update', Array.from(sosAlertsMap.values()).sort((a, b) => b.timestamp - a.timestamp));

  socket.on('register_peer', (data) => {
    let peerInfo;
    try {
      peerInfo = typeof data === 'string' ? JSON.parse(data) : data;
    } catch (e) {
      peerInfo = data;
    }

    const deviceId = peerInfo.deviceId || `device_${socket.id.substring(0, 6)}`;
    const displayName = peerInfo.displayName || 'Anonymous User';
    const clientType = peerInfo.clientType || 'web';

    activePeers.set(socket.id, { deviceId, displayName, clientType, socketId: socket.id });
    console.log(`[Registered] Peer: ${displayName} (${deviceId}) [${clientType}]`);
    broadcastPeerList();
  });

  socket.on('send_mesh_message', (payload) => {
    let msgData;
    try {
      msgData = typeof payload === 'string' ? JSON.parse(payload) : payload;
    } catch (e) {
      msgData = null;
    }

    // Check if message is an SOS distress payload
    if (msgData && (msgData.sosId || msgData.urgency)) {
      const sosId = msgData.sosId || `sos_${Date.now()}`;
      const sosObj = {
        sosId: sosId,
        victimName: msgData.victimName || msgData.senderName || 'Victim',
        victimDeviceId: msgData.victimDeviceId || msgData.senderId || 'device',
        urgency: msgData.urgency || 'CRITICAL',
        emergencyType: msgData.emergencyType || 'Medical Emergency',
        message: msgData.message || msgData.payload || 'SOS Emergency Alert',
        locationText: msgData.locationText || 'Disaster Area',
        latitude: msgData.latitude || null,
        longitude: msgData.longitude || null,
        batteryLevel: msgData.batteryLevel || 100,
        timestamp: msgData.timestamp || Date.now(),
        gatewayDeviceId: 'WebSocket Relay',
        status: 'PENDING',
        isSyncedToRescueTeam: true
      };

      sosAlertsMap.set(sosId, sosObj);
      console.log(`[🚨 REALTIME SOS] New alert received: ${sosObj.victimName} (${sosObj.emergencyType})`);
      io.emit('sos_alert_new', sosObj);
      broadcastSosAlerts();
    }

    socket.broadcast.emit('receive_mesh_message', payload);
  });

  socket.on('disconnect', () => {
    const peer = activePeers.get(socket.id);
    if (peer) {
      console.log(`[-] Peer disconnected: ${peer.displayName}`);
      activePeers.delete(socket.id);
      broadcastPeerList();
    }
  });
});

const PORT = process.env.PORT || 3000;
server.listen(PORT, () => {
  console.log(`=======================================================`);
  console.log(`🚀 ResxQ Rescue Operations Gateway Server on Port ${PORT}`);
  console.log(`🌐 Rescue Command Dashboard: http://localhost:${PORT}`);
  console.log(`⚡ Store-and-Forward Gateway Endpoint: POST http://localhost:${PORT}/api/sync-sos`);
  console.log(`=======================================================`);
});
