param([Parameter(Mandatory=$true)][string]$Path,
      [int]$X0 = -1, [int]$Y0 = -1, [int]$X1 = -1, [int]$Y1 = -1)

Add-Type -AssemblyName System.Drawing
$bmp = New-Object System.Drawing.Bitmap($Path)
$w = $bmp.Width; $h = $bmp.Height
if ($X0 -lt 0) { $X0 = 0; $Y0 = 0; $X1 = $w; $Y1 = $h }
$X1 = [Math]::Min($X1, $w); $Y1 = [Math]::Min($Y1, $h)

$rect = New-Object System.Drawing.Rectangle(0, 0, $w, $h)
$data = $bmp.LockBits($rect, [System.Drawing.Imaging.ImageLockMode]::ReadOnly,
                      [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$stride = $data.Stride
$buf = New-Object byte[] ($stride * $h)
[System.Runtime.InteropServices.Marshal]::Copy($data.Scan0, $buf, 0, $buf.Length)
$bmp.UnlockBits($data)
$bmp.Dispose()

$sum = [double]0; $n = 0
for ($y = $Y0; $y -lt $Y1; $y += 3) {
  $row = $y * $stride
  for ($x = $X0; $x -lt $X1; $x += 3) {
    $i = $row + $x * 4
    $sum += $buf[$i] + $buf[$i+1] + $buf[$i+2]; $n += 3
  }
}
"{0}x{1} rect={2},{3},{4},{5} mean={6:N2}" -f $w, $h, $X0, $Y0, $X1, $Y1, ($sum / $n)
