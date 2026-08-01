# MySQL setup

1. Set in config.yml:
```yaml
database:
  type: mysql
  pool-size: 10
  mysql:
    host: localhost
    port: 3306
    database: novacrates
    username: root
    password: secret
    use-ssl: false
```

2. Add driver to server:
   - Download `mysql-connector-j-8.3.0.jar`
   - Put into `plugins/` or use Paper libraries

3. Schema is auto-created on first start (schema_version=3).

SQLite remains default (`database.type: sqlite`).
