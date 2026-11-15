param(
  [string]$TextureDir = "src\main\resources\assets\gsm\textures\block",
  [string]$OutPath = "src\main\resources\assets\gsm\icon.png",
  [int]$Size = 128
)
# Draws the mod icon: the Storage Module's side texture (copper hoops, red rivets), scaled up
# with crisp pixels. Simple and readable at small sizes, like in the mod list.
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
