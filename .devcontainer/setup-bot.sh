#!/bin/bash
# Codespace post-create setup for Telegram Bot

set -e

echo "========================================="
echo "  Setting up Telegram Bot"
echo "========================================="

cd /workspaces/rat-project/bot

# Install Python dependencies
echo "Installing Python dependencies..."
pip install --no-cache-dir -r requirements.txt

# Check if .env exists, if not create from example
if [ ! -f .env ]; then
    echo "Creating .env from example..."
    cp ../.env.example .env
    echo ""
    echo "⚠️  IMPORTANT: Edit /workspaces/rat-project/bot/.env and add your TELEGRAM_BOT_TOKEN"
    echo "   Get token from @BotFather on Telegram"
    echo "   Also add your user ID to TELEGRAM_ADMIN_USERS (get from @userinfobot)"
    echo ""
fi

# Validate bot syntax
echo "Validating bot syntax..."
python3 -m py_compile bot.py

echo ""
echo "========================================="
echo "  Telegram Bot Setup Complete!"
echo "========================================="
echo ""
echo "Next steps:"
echo "1. Edit /workspaces/rat-project/bot/.env with your bot token"
echo "2. Run: python3 bot.py"
echo "3. Or use: ./start_all.sh to start everything"
echo ""