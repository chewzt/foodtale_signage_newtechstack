# LAN protocol

Ports (all bind `0.0.0.0`):

| Port | Proto | Who | What |
|---|---|---|---|
| 8080 | TCP HTTP | TV → Pi | pair, manifest, media, admin |
| 8123 | UDP | TV ↔ Pi | Cristian clock (unicast reply) |
| 48720 | UDP | Pi → LAN | CMS beacon `255.255.255.255` |
| 48721 | UDP | leader TV → LAN | START / HEARTBEAT |

## Beacon (Pi, 1s)

```json
{ "v": 1, "service": "foodtale-cms", "cmsId": "…", "http": "http://192.168.1.50:8080", "clockPort": 8123 }
```

## Clock

Request `{ "v": 1, "t1": 123 }` (`t1` = client `elapsedRealtime`).
Reply `{ "v": 1, "t1": 123, "tMaster": 456, "t2send": 457 }` (`tMaster` = Pi `CLOCK_BOOTTIME` ms).

`offset = ((tMaster - t1) + (t2send - t3)) / 2`
`synced = elapsedRealtime + offset`

## Play bus (leader)

```json
{ "v": 1, "action": "START", "group": "p12|3", "itemId": "8", "targetEpoch": 9000, "posMs": 0, "sendTime": 8700 }
```

```json
{ "v": 1, "action": "HEARTBEAT", "group": "p12|3", "itemId": "8", "targetEpoch": 0, "posMs": 1400, "sendTime": 10400 }
```

`targetEpoch` / `sendTime` are Pi virtual clock ms. Leader = lowest paired `deviceId` in the playlist. Fail over if silent > 2s.

## HTTP

`POST /api/pair` `{ pairing_code, device_name }` → `{ device_token, cms_id, device_id, http }`

`GET /api/device/manifest` `Authorization: Bearer <token>`

`POST /api/device/heartbeat` same auth

`GET /api/health` `{ cms_id, http, master_ms }`
