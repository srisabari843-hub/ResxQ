document.addEventListener('DOMContentLoaded', () => {
  // UI Elements
  const criticalSosCount = document.getElementById('criticalSosCount');
  const gatewayNodeCount = document.getElementById('gatewayNodeCount');
  const rescuedCount = document.getElementById('rescuedCount');
  const serverStatusBadge = document.getElementById('serverStatusBadge');
  const serverStatusText = document.getElementById('serverStatusText');
  const toggleAudioBtn = document.getElementById('toggleAudioBtn');
  const urgencyFilter = document.getElementById('urgencyFilter');
  const statusFilter = document.getElementById('statusFilter');
  const exportCsvBtn = document.getElementById('exportCsvBtn');
  const peerCountLabel = document.getElementById('peerCountLabel');
  const peersContainer = document.getElementById('peersContainer');
  const sosAlertsContainer = document.getElementById('sosAlertsContainer');

  let sosAlertsList = [];
  let isAudioEnabled = true;

  // Toggle Audio Alarm
  toggleAudioBtn.addEventListener('click', () => {
    isAudioEnabled = !isAudioEnabled;
    toggleAudioBtn.className = `btn-toggle ${isAudioEnabled ? 'active' : ''}`;
    toggleAudioBtn.innerHTML = isAudioEnabled
      ? '<i class="fa-solid fa-volume-high"></i> ON'
      : '<i class="fa-solid fa-volume-xmark"></i> OFF';
  });

  // Web Audio API Emergency Siren Synthesizer
  function playEmergencySiren() {
    if (!isAudioEnabled) return;
    try {
      const AudioCtx = window.AudioContext || window.webkitAudioContext;
      if (!AudioCtx) return;
      const ctx = new AudioCtx();

      const osc = ctx.createOscillator();
      const gain = ctx.createGain();

      osc.type = 'sawtooth';
      osc.frequency.setValueAtTime(880, ctx.currentTime); // A5
      osc.frequency.exponentialRampToValueAtTime(440, ctx.currentTime + 0.3); // A4

      gain.gain.setValueAtTime(0.3, ctx.currentTime);
      gain.gain.exponentialRampToValueAtTime(0.01, ctx.currentTime + 0.5);

      osc.connect(gain);
      gain.connect(ctx.destination);

      osc.start();
      osc.stop(ctx.currentTime + 0.5);
    } catch (e) {
      console.warn('Audio play error:', e);
    }
  }

  // Socket.IO Connection
  const socket = io();

  socket.on('connect', () => {
    serverStatusBadge.className = 'server-status';
    serverStatusText.textContent = 'Gateway Active';
    socket.emit('register_peer', {
      deviceId: 'rescue_cmd_web',
      displayName: 'Rescue Command Center',
      clientType: 'web'
    });
  });

  socket.on('disconnect', () => {
    serverStatusBadge.className = 'server-status disconnected';
    serverStatusText.textContent = 'Gateway Disconnected';
  });

  // Handle incoming list of SOS Alerts
  socket.on('sos_alerts_update', (alerts) => {
    sosAlertsList = alerts || [];
    renderDashboard();
  });

  // Handle newly arrived single SOS alert (Play Siren!)
  socket.on('sos_alert_new', (newAlert) => {
    playEmergencySiren();
    // Add if not already present
    const idx = sosAlertsList.findIndex(a => a.sosId === newAlert.sosId);
    if (idx >= 0) {
      sosAlertsList[idx] = newAlert;
    } else {
      sosAlertsList.unshift(newAlert);
    }
    renderDashboard();
  });

  // Handle peer nodes update
  socket.on('peers_update', (peers) => {
    peerCountLabel.textContent = peers.length;
    gatewayNodeCount.textContent = peers.filter(p => p.clientType === 'android').length;

    if (!peers || peers.length === 0) {
      peersContainer.innerHTML = '<div class="empty-text">No mesh devices connected</div>';
      return;
    }

    peersContainer.innerHTML = peers.map(peer => `
      <div class="peer-node-item">
        <i class="fa-solid ${peer.clientType === 'android' ? 'fa-mobile-screen-button' : 'fa-laptop'}"></i>
        <span>${escapeHtml(peer.displayName)}</span>
        <small style="margin-left:auto; color:var(--text-muted); font-family:monospace;">${peer.deviceId}</small>
      </div>
    `).join('');
  });

  // Filter Listeners
  urgencyFilter.addEventListener('change', renderDashboard);
  statusFilter.addEventListener('change', renderDashboard);

  // Render Dashboard Cards & Metrics
  function renderDashboard() {
    // 1. Update Metrics
    const criticals = sosAlertsList.filter(a => a.urgency === 'CRITICAL' && a.status !== 'RESCUED').length;
    const rescued = sosAlertsList.filter(a => a.status === 'RESCUED').length;

    criticalSosCount.textContent = criticals;
    rescuedCount.textContent = rescued;

    // 2. Filter Alerts
    const selectedUrgency = urgencyFilter.value;
    const selectedStatus = statusFilter.value;

    let filtered = sosAlertsList;
    if (selectedUrgency !== 'ALL') {
      filtered = filtered.filter(a => a.urgency === selectedUrgency);
    }
    if (selectedStatus !== 'ALL') {
      filtered = filtered.filter(a => a.status === selectedStatus);
    }

    if (filtered.length === 0) {
      sosAlertsContainer.innerHTML = `
        <div class="loading-state">
          <i class="fa-solid fa-shield-cat"></i>
          <p>No distress signals matching selected filters</p>
        </div>
      `;
      return;
    }

    sosAlertsContainer.innerHTML = filtered.map(alert => renderSosCard(alert)).join('');

    // Attach Action Event Handlers for Status Toggles
    document.querySelectorAll('.btn-status').forEach(btn => {
      btn.addEventListener('click', (e) => {
        const sosId = e.currentTarget.getAttribute('data-id');
        const targetStatus = e.currentTarget.getAttribute('data-status');
        updateSosStatus(sosId, targetStatus);
      });
    });
  }

  function renderSosCard(alert) {
    const timeStr = new Date(alert.timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
    const dateStr = new Date(alert.timestamp).toLocaleDateString();

    const isPending = alert.status === 'PENDING';
    const isDispatched = alert.status === 'DISPATCHED';
    const isRescued = alert.status === 'RESCUED';

    return `
      <div class="sos-card ${alert.urgency}" id="card_${alert.sosId}">
        <div class="sos-header">
          <div class="victim-info">
            <h3>🚨 ${escapeHtml(alert.victimName)}</h3>
            <span class="victim-device">ID: ${escapeHtml(alert.victimDeviceId)}</span>
          </div>
          <span class="urgency-badge ${alert.urgency}">${alert.urgency}</span>
        </div>

        <div class="sos-body">
          <div class="emergency-type">
            <i class="fa-solid fa-kit-medical"></i> ${escapeHtml(alert.emergencyType)}
          </div>
          <p class="sos-message-text">"${escapeHtml(alert.message)}"</p>
          
          <div class="sos-meta-grid">
            <div class="meta-item"><i class="fa-solid fa-location-dot"></i> ${escapeHtml(alert.locationText)}</div>
            <div class="meta-item"><i class="fa-solid fa-battery-three-quarters"></i> Battery: ${alert.batteryLevel}%</div>
            <div class="meta-item"><i class="fa-solid fa-clock"></i> ${dateStr} ${timeStr}</div>
            <div class="meta-item"><i class="fa-solid fa-tag"></i> Status: <strong>${alert.status}</strong></div>
          </div>
        </div>

        <div class="sos-gateway-tag">
          <i class="fa-solid fa-network-wired"></i> Synced via Gateway: ${escapeHtml(alert.gatewayDeviceId || 'Mesh Gateway')}
        </div>

        <div class="sos-actions">
          <button class="btn-status dispatch ${isDispatched ? 'active-status' : ''}" data-id="${alert.sosId}" data-status="DISPATCHED">
            <i class="fa-solid fa-helicopter"></i> ${isDispatched ? 'Dispatched' : 'Dispatch Team'}
          </button>
          <button class="btn-status rescued ${isRescued ? 'active-status' : ''}" data-id="${alert.sosId}" data-status="RESCUED">
            <i class="fa-solid fa-circle-check"></i> ${isRescued ? 'Rescued' : 'Mark Rescued'}
          </button>
        </div>
      </div>
    `;
  }

  // Update Status API Call
  function updateSosStatus(sosId, newStatus) {
    fetch(`/api/sos-alerts/${sosId}/status`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ status: newStatus })
    })
    .then(res => res.json())
    .then(data => {
      if (data.success) {
        const item = sosAlertsList.find(a => a.sosId === sosId);
        if (item) item.status = newStatus;
        renderDashboard();
      }
    })
    .catch(err => console.error('Failed to update status', err));
  }

  // Export Incident Report CSV
  exportCsvBtn.addEventListener('click', () => {
    if (sosAlertsList.length === 0) {
      alert('No SOS distress signals to export.');
      return;
    }

    const headers = ['SOS ID', 'Victim Name', 'Device ID', 'Urgency', 'Emergency Type', 'Message', 'Location', 'Battery %', 'Timestamp', 'Gateway Device', 'Rescue Status'];
    const rows = sosAlertsList.map(a => [
      a.sosId,
      `"${a.victimName.replace(/"/g, '""')}"`,
      a.victimDeviceId,
      a.urgency,
      `"${a.emergencyType.replace(/"/g, '""')}"`,
      `"${a.message.replace(/"/g, '""')}"`,
      `"${a.locationText.replace(/"/g, '""')}"`,
      a.batteryLevel,
      new Date(a.timestamp).toISOString(),
      a.gatewayDeviceId,
      a.status
    ]);

    const csvContent = 'data:text/csv;charset=utf-8,' + [headers.join(','), ...rows.map(e => e.join(','))].join('\n');
    const encodedUri = encodeURI(csvContent);
    const link = document.createElement('a');
    link.setAttribute('href', encodedUri);
    link.setAttribute('download', `ResxQ_SOS_Incident_Report_${Date.now()}.csv`);
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
  });

  function escapeHtml(str) {
    return String(str || '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  // Initial Fetch
  fetch('/api/sos-alerts')
    .then(res => res.json())
    .then(data => {
      sosAlertsList = data || [];
      renderDashboard();
    })
    .catch(_ => {});
});
