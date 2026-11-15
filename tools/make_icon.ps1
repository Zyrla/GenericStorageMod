param(
  [string]$TextureDir = "src\main\resources\assets\gsm\textures\block",
  [string]$OutPath = "src\main\resources\assets\gsm\icon.png"
)
# Draws the mod icon: a 128x128 isometric Storage Module made from the block's own textures
# (barrel-bottom top face, copper-hooped sides), shaded like a block in the inventory.
Add-Type -AssemblyName System.Drawing

$side = [System.Drawing.Bitmap]::FromFile((Resolve-Path (Join-Path $TextureDir 'storage_module_side.png')))
$end = [System.Drawing.Bitmap]::FromFile((Resolve-Path (Join-Path $TextureDir 'storage_module_bottom.png')))

$size = 128
$icon = New-Object System.Drawing.Bitmap $size, $size, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$g = [System.Drawing.Graphics]::FromImage($icon)
$g.Clear([System.Drawing.Color]::Transparent)
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
$g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half

# Cube corners (isometric). Top face is a rhombus; the two sides hang below it.
$cx = 64.0; $top = 8.0; $w = 56.0; $h = 28.0; $depth = 64.0
function Pt([double]$x, [double]$y) { New-Object System.Drawing.PointF ([single]$x), ([single]$y) }
$n = Pt $cx $top                       # back corner of the top face
$e = Pt ($cx + $w) ($top + $h)         # right
$s = Pt $cx ($top + 2 * $h)            # front
$wpt = Pt ($cx - $w) ($top + $h)       # left

function Face($bitmap, $p0, $p1, $p2, [single]$brightness) {
  # DrawImage with three points maps the image onto a parallelogram (upper-left, upper-right, lower-left).
  $attributes = New-Object System.Drawing.Imaging.ImageAttributes
  $m = New-Object System.Drawing.Imaging.ColorMatrix
  $m.Matrix00 = $brightness; $m.Matrix11 = $brightness; $m.Matrix22 = $brightness
  $attributes.SetColorMatrix($m)
  $points = [System.Drawing.PointF[]]@($p0, $p1, $p2)
  $g.DrawImage($bitmap, $points, (New-Object System.Drawing.RectangleF 0, 0, 16, 16), [System.Drawing.GraphicsUnit]::Pixel, $attributes)
}

# Top (full light), left side (a little darker), right side (darker still), like vanilla item renders.
Face $end $wpt $n $s 1.0
Face $side $wpt $s (Pt $wpt.X ($wpt.Y + $depth)) 0.8
Face $side $s $e (Pt $s.X ($s.Y + $depth)) 0.62

$icon.Save($OutPath, [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $icon.Dispose(); $side.Dispose(); $end.Dispose()
"ok"
