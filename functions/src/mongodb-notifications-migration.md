# CastDoo notification migration

Push delivery has moved to the MongoDB-backed Admin API using UnifiedPush/Web Push. The Firebase callable `sendAdminNotification` is intentionally removed. Do not deploy the former Firebase notification function. User authentication and the stable release callable remain separate services.
