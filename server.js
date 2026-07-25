// Talkie signaling server
// Relays WebRTC offers/answers/ICE candidates between devices in the same
// "channel" (room), and broadcasts push-to-talk on/off events.
// It never sees or touches the actual audio - that flows peer-to-peer.

const express = require('express');
const http = require('http');
const path = require('path');
const { WebSocketServer } = require('ws');

const app = express();
app.use(express.static(path.join(__dirname, 'public')));

const server = http.createServer(app);
const wss = new WebSocketServer({ server });

// channelId -> Map<peerId, ws>
const channels = new Map();

function getChannel(channelId) {
  if (!channels.has(channelId)) channels.set(channelId, new Map());
  return channels.get(channelId);
}

function send(ws, obj) {
  if (ws && ws.readyState === ws.OPEN) ws.send(JSON.stringify(obj));
}

wss.on('connection', (ws) => {
  let channelId = null;
  let peerId = null;

  ws.on('message', (raw) => {
    let msg;
    try { msg = JSON.parse(raw); } catch (e) { return; }

    // A device joining a channel (walkie-talkie "frequency")
    if (msg.type === 'join') {
      channelId = msg.channel;
      peerId = msg.peerId;
      ws.peerName = msg.name || 'Someone';
      const channel = getChannel(channelId);

      // Tell everyone already here that a new device arrived.
      for (const [, existingWs] of channel) {
        send(existingWs, { type: 'peer-joined', peerId, name: ws.peerName });
      }

      // Tell the new device who is already on this channel.
      const existingPeers = [...channel.entries()].map(([id, w]) => ({ peerId: id, name: w.peerName }));
      send(ws, { type: 'channel-state', peers: existingPeers });

      channel.set(peerId, ws);
      return;
    }

    if (!channelId) return;
    const channel = getChannel(channelId);

    // WebRTC handshake relay (offer / answer / ice candidates)
    if (msg.type === 'signal' && msg.to) {
      const target = channel.get(msg.to);
      if (target) send(target, { type: 'signal', from: peerId, data: msg.data });
      return;
    }

    // Push-to-talk button pressed/released - broadcast to the channel
    if (msg.type === 'talk-state') {
      for (const [id, w] of channel) {
        if (id !== peerId) send(w, { type: 'talk-state', peerId, talking: !!msg.talking, name: ws.peerName });
      }
      return;
    }
  });

  ws.on('close', () => {
    if (!channelId || !peerId) return;
    const channel = getChannel(channelId);
    channel.delete(peerId);
    for (const [, w] of channel) send(w, { type: 'peer-left', peerId });
    if (channel.size === 0) channels.delete(channelId);
  });
});

const PORT = process.env.PORT || 3000;
server.listen(PORT, () => console.log('Talkie signaling server listening on port ' + PORT));
