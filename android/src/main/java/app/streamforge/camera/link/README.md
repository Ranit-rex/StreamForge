# Future remote camera input

Remote/mobile-linked camera sources will live here, separate from the local
CameraX implementation in `../local`.

Planned integration boundary:
- Pair/connect a remote device independently of the local camera.
- Negotiate a secure stream endpoint and session token.
- Expose frames through a preview surface/provider that Scene Studio can place
  as a normal camera layer.
- Keep connection lifecycle, reconnect, permissions, and transport code here;
  do not put remote transport logic in Scene Studio or the local CameraX source.

No network camera transport is enabled yet. The current Camera layer uses the
phone's built-in front/back camera only.
