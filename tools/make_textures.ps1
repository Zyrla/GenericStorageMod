param(
  [Parameter(Mandatory = $true)][string]$BarrelDir,   # folder holding vanilla barrel_side.png and barrel_bottom.png
  [Parameter(Mandatory = $true)][string]$OutDir,
  [string]$PreviewPath
)
# Builds the Storage Module textures from the vanilla barrel textures: the side's iron hoops become
# copper with red rivets, and the bottom (used for both ends) gets copper corner brackets.
Add-Type -AssemblyName System.Drawing

function Hex([string]$h) {
  [System.Drawing.Color]::FromArgb(255, [Convert]::ToInt32($h.Substring(0, 2), 16), [Convert]::ToInt32($h.Substring(2, 2), 16), [Convert]::ToInt32($h.Substring(4, 2), 16))
}
function Key([System.Drawing.Color]$c) { '{0:X2}{1:X2}{2:X2}' -f $c.R, $c.G, $c.B }

# Copper and red shades.
$copperLight = 'E8925F'; $copperMid = 'D06A3E'; $copperDark = 'A8502F'; $copperDeep = '6B3324'
$redBright = 'F04030'; $red = 'B81C1C'; $redDark = '7A0E0E'

# Vanilla grey/iron colors -> copper or red, keeping their light and shade.
$sideRecolor = @{ '453F3B' = $copperLight; '302C29' = $copperMid; '28221C' = $copperDark }

function Recolor($bmp, $map) {
  for ($y = 0; $y -lt 16; $y++) { for ($x = 0; $x -lt 16; $x++) {
    $k = Key $bmp.GetPixel($x, $y)
    if ($map.ContainsKey($k)) { $bmp.SetPixel($x, $y, (Hex $map[$k])) }
  } }
}

function Paint($bmp, [int[]]$points, [string]$color) {
  for ($i = 0; $i -lt $points.Count; $i += 2) { $bmp.SetPixel($points[$i], $points[$i + 1], (Hex $color)) }
}

# Small copper L-brackets in each corner.
function CornerBrackets($bmp) {
  foreach ($corner in @(@(0, 0, 1, 1), @(15, 0, -1, 1), @(0, 15, 1, -1), @(15, 15, -1, -1))) {
    $cx = $corner[0]; $cy = $corner[1]; $dx = $corner[2]; $dy = $corner[3]
    $lit = ($dy -eq 1 -and $dx -eq 1)
    Paint $bmp @($cx, $cy) $(if ($lit) { $copperLight } else { $copperMid })
    Paint $bmp @(($cx + $dx), $cy, ($cx + 2 * $dx), $cy) $(if ($dy -eq 1) { $copperLight } else { $copperDark })
    Paint $bmp @($cx, ($cy + $dy), $cx, ($cy + 2 * $dy)) $(if ($dx -eq 1) { $copperLight } else { $copperDark })
    Paint $bmp @(($cx + $dx), ($cy + $dy)) $copperDeep
  }
}

New-Item -ItemType Directory -Force $OutDir | Out-Null
$results = [ordered]@{}

# Side: copper hoops with red rivets.
$side = [System.Drawing.Bitmap]::FromFile((Join-Path $BarrelDir 'barrel_side.png'))
$side = New-Object System.Drawing.Bitmap $side
Recolor $side $sideRecolor
foreach ($hoopRow in 3, 11) {
  foreach ($x in 3, 12) {
    Paint $side @($x, $hoopRow) $redBright
    Paint $side @($x, ($hoopRow + 1)) $redDark
  }
}
$results['storage_module_side'] = $side

# Bottom (also used for the top): copper corners.
$bottom = [System.Drawing.Bitmap]::FromFile((Join-Path $BarrelDir 'barrel_bottom.png'))
$bottom = New-Object System.Drawing.Bitmap $bottom
CornerBrackets $bottom
$results['storage_module_bottom'] = $bottom

# Upgrade items: a copper-framed plate with a 12x12 window onto a vanilla texture.
function UpgradePlate($source) {
  $plate = New-Object System.Drawing.Bitmap 16, 16, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
  for ($y = 1; $y -le 14; $y++) { for ($x = 1; $x -le 14; $x++) {
    if ($x -eq 1 -or $y -eq 1) { $plate.SetPixel($x, $y, (Hex $copperLight)) }
    elseif ($x -eq 14 -or $y -eq 14) { $plate.SetPixel($x, $y, (Hex $copperDark)) }
    else { $plate.SetPixel($x, $y, $source.GetPixel($x, $y)) }
  } }
  # Red corner rivets.
  foreach ($p in @(@(1, 1), @(14, 1), @(1, 14), @(14, 14))) { $plate.SetPixel($p[0], $p[1], (Hex $redBright)) }
  return $plate
}

$results['crafting_upgrade'] = UpgradePlate ([System.Drawing.Bitmap]::FromFile((Join-Path $BarrelDir 'crafting_table_top.png')))

# The capacity plate shows the module's own copper-hooped side.
$capacity = UpgradePlate $results['storage_module_side']
# A red plus: "more slots".
foreach ($p in @(@(7, 5), @(7, 6), @(5, 7), @(6, 7), @(7, 7), @(8, 7), @(9, 7), @(7, 8), @(7, 9))) { $capacity.SetPixel($p[0], $p[1], (Hex $redBright)) }
foreach ($p in @(@(8, 5), @(8, 6), @(10, 7), @(8, 8), @(8, 9), @(6, 8), @(9, 8))) { $capacity.SetPixel($p[0], $p[1], (Hex $redDark)) }
$results['capacity_upgrade'] = $capacity

# Capacity II: the same plate with gold rivets and two gold pips ("II") in the top-left.
$gold = 'FCDB4A'; $goldDark = 'C9A227'
$capacity2 = New-Object System.Drawing.Bitmap $capacity
foreach ($p in @(@(1, 1), @(14, 1), @(1, 14), @(14, 14))) { $capacity2.SetPixel($p[0], $p[1], (Hex $gold)) }
foreach ($p in @(@(3, 3), @(3, 4), @(5, 3), @(5, 4))) { $capacity2.SetPixel($p[0], $p[1], (Hex $gold)) }
foreach ($p in @(@(3, 5), @(5, 5))) { $capacity2.SetPixel($p[0], $p[1], (Hex $goldDark)) }
$results['capacity_upgrade_2'] = $capacity2

foreach ($name in $results.Keys) {
  $folder = if ($name -like '*_upgrade*') { Join-Path $OutDir '..\item' } else { $OutDir }
  New-Item -ItemType Directory -Force $folder | Out-Null
  $results[$name].Save((Join-Path $folder "$name.png"), [System.Drawing.Imaging.ImageFormat]::Png)
}

if ($PreviewPath) {
  $scale = 16; $gap = 16
  $preview = New-Object System.Drawing.Bitmap (($results.Count * (16 * $scale + $gap)) + $gap), (16 * $scale + 2 * $gap)
  $g = [System.Drawing.Graphics]::FromImage($preview)
  $g.Clear([System.Drawing.Color]::FromArgb(255, 40, 40, 40))
  $g.InterpolationMode = 'NearestNeighbor'
  $g.PixelOffsetMode = 'Half'
  $i = 0
  foreach ($name in $results.Keys) {
    $g.DrawImage($results[$name], $gap + $i * (16 * $scale + $gap), $gap, 16 * $scale, 16 * $scale)
    $i++
  }
  $preview.Save($PreviewPath, [System.Drawing.Imaging.ImageFormat]::Png)
}
"ok"
