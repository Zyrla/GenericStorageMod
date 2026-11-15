param(
  [string]$TextureDir = "src\main\resources\assets\gsm\textures\block",
  [string]$OutPath = "tools\icon_base.png",
  [int]$Size = 128
)
# Draws a plain icon base: the Storage Module's side texture (copper hoops, red rivets), scaled up
# with crisp pixels. The real mod icon (src/main/resources/assets/gsm/icon.png, with the "GSM" text)
# is hand-made from this base, so this script writes to tools/ by default and never overwrites it.
Add-Type -AssemblyName System.Drawing

$side = [System.Drawing.Bitmap]::FromFile((Resolve-Path (Join-Path $TextureDir 'storage_module_side.png')))
$icon = New-Object System.Drawing.Bitmap $Size, $Size, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$g = [System.Drawing.Graphics]::FromImage($icon)
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
$g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
$g.DrawImage($side, 0, 0, $Size, $Size)

$icon.Save($OutPath, [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $icon.Dispose(); $side.Dispose()
"ok"
