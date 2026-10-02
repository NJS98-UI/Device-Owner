param([Parameter(Mandatory=$true)][string]$Dir)

Add-Type -AssemblyName System.Drawing

# 1920x1080 grid, labels sit at each cell's top-left
$cells = @(
  @{ n = 'AVM1/id4'; x0 = 0;    y0 = 490; x1 = 640;  y1 = 750 },
  @{ n = 'AVM2/id5'; x0 = 640;  y0 = 490; x1 = 1280; y1 = 750 },
  @{ n = 'AVM3/id6'; x0 = 1280; y0 = 490; x1 = 1920; y1 = 750 },
  @{ n = 'AVM4/id7'; x0 = 0;    y0 = 830; x1 = 640;  y1 = 1080 }
)

function Get-CellMeans([string]$path) {
  $bmp = New-Object System.Drawing.Bitmap($path)
  $w = $bmp.Width; $h = $bmp.Height
  $rect = New-Object System.Drawing.Rectangle(0, 0, $w, $h)
  $data = $bmp.LockBits($rect, [System.Drawing.Imaging.ImageLockMode]::ReadOnly,
                        [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
  $stride = $data.Stride
  $buf = New-Object byte[] ($stride * $h)
  [System.Runtime.InteropServices.Marshal]::Copy($data.Scan0, $buf, 0, $buf.Length)
  $bmp.UnlockBits($data); $bmp.Dispose()

  $res = @()
  foreach ($c in $cells) {
    $x1 = [Math]::Min($c.x1, $w); $y1 = [Math]::Min($c.y1, $h)
    $sum = [double]0; $n = 0
    for ($y = $c.y0; $y -lt $y1; $y += 2) {
      $row = $y * $stride
      for ($x = $c.x0; $x -lt $x1; $x += 2) {
        $i = $row + $x * 4
        $sum += $buf[$i] + $buf[$i+1] + $buf[$i+2]; $n += 3
      }
    }
    $res += ($sum / $n)
  }
  return @{ w = $w; h = $h; m = $res }
}

"{0,-6} {1,9} {2,9} {3,9} {4,9}" -f 'frame', $cells[0].n, $cells[1].n, $cells[2].n, $cells[3].n
Get-ChildItem -Path $Dir -Filter *.png | Sort-Object Name | ForEach-Object {
  try {
    $r = Get-CellMeans $_.FullName
    "{0,-6} {1,9:N1} {2,9:N1} {3,9:N1} {4,9:N1}   {5}x{6}" -f $_.BaseName, $r.m[0], $r.m[1], $r.m[2], $r.m[3], $r.w, $r.h
  } catch {
    "{0,-6}  FAILED: {1}" -f $_.BaseName, $_.Exception.Message
  }
}
