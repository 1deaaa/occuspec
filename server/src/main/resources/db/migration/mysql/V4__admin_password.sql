-- V4：默认账号密码哈希修正为 SHA-256 加盐哈希（与 PasswordHasher 同算法）。
UPDATE sys_users
SET password_hash = 'sha256$2d0eee5430c6601cd443bc5a328ecb84a51b7b27c6a0a1bab3ba78f64626857f'
WHERE username = 'admin';
