# 🚁 ResxQ - Disaster Rescue Team Web Application & Store-and-Forward Gateway

This standalone web application serves as the **Emergency Operations Command Center** for the **ResxQ** disaster rescue system.

---

## 📂 Folder Structure

```text
ResxQ_Web_Application/
├── package.json        # Node.js project dependencies (express, socket.io, cors)
├── server.js            # Express & Socket.IO Store-and-Forward Gateway Server
├── README.md            # Setup & Usage Documentation
└── public/              # Static Web Application Client
    ├── index.html       # Rescue Command Center Dashboard UI
    ├── style.css        # Emergency SOS & Glassmorphism Styling
    └── app.js           # Real-Time Socket.IO, Audio Siren & CSV Exporter
```

---

## ⚡ Key Features

1. **Automatic Store-and-Forward Gateway (`POST /api/sync-sos`)**:
   - Accepts offline SOS distress batches automatically uploaded whenever an Android phone in the mesh connects to cell network/Wi-Fi.

2. **Real-Time Emergency Siren Alarm**:
   - Web Audio API synthesizer plays a dual-tone emergency siren alarm when new victim distress signals arrive.

3. **Victim Distress Tracking Feed**:
   - Displays victim name, device ID, urgency grade (`CRITICAL`, `HIGH`, `MEDIUM`), emergency category (*Medical, Trapped, Food/Water*), GPS location coordinates, battery percentage, timestamp, and gateway device ID.

4. **Rescue Dispatch Action Controls**:
   - Allows rescue officers to toggle incident status: **`PENDING`** $\rightarrow$ **`DISPATCHED`** $\rightarrow$ **`RESCUED`**.

5. **Incident Report CSV Exporter**:
   - One-click download of field report spreadsheet (`.csv`) for rescue deployment teams.

---

## 🚀 How to Run Standalone

### 1. Install Dependencies
Open terminal inside `ResxQ_Web_Application`:
```bash
npm install
```

### 2. Start the Gateway Server
```bash
npm start
```

The server will run on:
- **Dashboard**: `http://localhost:3000`
- **Gateway Endpoint**: `http://localhost:3000/api/sync-sos`

---

## 🌐 Public Internet Tunnel (For Mobile 4G/5G Connectivity)

To allow Android phones on 4G/5G mobile data to sync with this web app, expose port `3000` using any tunnel tool:

```bash
npx -y localtunnel --port 3000
```
OR
```bash
ssh -o StrictHostKeyChecking=no -R 80:localhost:3000 nokey@localhost.run
```
