# Windows 11 Wake-on-LAN Setup Checklist

Manual, one-time setup on the target PC. Do this **before** testing any code — if Wake-on-LAN doesn't work after this checklist, it's almost certainly a BIOS/hardware limitation, not a bug in the relay.

## 1. BIOS / UEFI

- [ ] Enable **Wake on LAN** — may be labeled "Power On By PCI-E/PME," "Resume By PME," or similar depending on motherboard vendor. Usually under Power Management settings.
- [ ] Disable **ERP / EuP Ready** (sometimes "EuP 2013" or "Deep Sleep"). This energy-saving mode cuts standby power to the NIC entirely, which silently defeats WoL with no error message anywhere. This is the single most common root cause of "WoL just doesn't work."
- [ ] Save and exit.

## 2. Windows Device Manager

Device Manager → Network adapters → (your physical Ethernet/Wi-Fi NIC) → Properties:

- [ ] **Power Management** tab: check "Allow this device to wake the computer." Uncheck "Allow the computer to turn off this device to save power."
- [ ] **Advanced** tab: find "Wake on Magic Packet" (exact name varies by NIC vendor/driver) and set it to **Enabled**.

## 3. Windows Power Options

Control Panel → Power Options → "Choose what the power buttons do" → "Change settings that are currently unavailable":

- [ ] Uncheck **"Turn on fast startup (recommended)."** Fast Startup makes a "shutdown" behave like a hybrid hibernate (closer to S4) instead of a true full power-off (S5), and is known to block WoL-from-shutdown on many systems.

## 4. Router

- [ ] Set a **DHCP reservation / static lease** for the PC's NIC MAC address, so its LAN IP never changes. The relay's `devices.yaml` records a fixed `lan_ip` — if it drifts, status checks will silently target the wrong (or no) address.

## 5. Windows Firewall

- [ ] Confirm **"Core Networking - Echo Request (ICMPv4-In)"** is enabled on the **Private** profile (needed for the relay's ping-based status check). This is usually on by default but worth verifying:
  ```powershell
  Get-NetFirewallRule -DisplayName "*Echo Request*" | Select DisplayName, Enabled, Profile
  ```

## 6. Network profile

- [ ] Confirm the home network connection is set to **"Private,"** not "Public," in Windows network settings (Settings → Network & Internet). Several firewall defaults differ by profile, and firewall rules scoped to `-Profile Private` won't match a network Windows considers Public.

## 7. Verify

- [ ] Run `powercfg /a` in an elevated terminal and confirm **S5** (or "Soft Off") is listed as an available sleep state, and that the PC actually reaches it on shutdown (not just display-off).

## Known hardware limitations (no software fix)

- Some OEM/laptop motherboards never expose true **S5** WoL in BIOS at all — only S3 (sleep). If after this entire checklist WoL still doesn't work, check community reports for your exact motherboard/NIC model before assuming a config mistake.
- **USB-to-Ethernet adapters** generally don't support WoL-from-S5 regardless of settings — only onboard/PCIe NICs with PME wiring to the chipset reliably do.
- If the NAS and PC are ever split across VLANs (e.g. a guest/IoT VLAN), the broadcast magic packet won't cross the boundary — they must share one Layer 2 segment.
