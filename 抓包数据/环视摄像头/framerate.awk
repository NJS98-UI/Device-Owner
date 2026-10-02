/CamProbe/ {
  if (index($0, "id4") == 0 && index($0, "id5") == 0 && index($0, "id6") == 0 && index($0, "id7") == 0) next
  p = index($0, "frames=")
  if (p == 0) next
  if (index($0, "id4") > 0) id = "id4"
  else if (index($0, "id5") > 0) id = "id5"
  else if (index($0, "id6") > 0) id = "id6"
  else id = "id7"
  f = substr($0, p + 7) + 0
  ts = $2
  split(ts, t, ":")
  s = t[3] + 60 * t[2] + 3600 * t[1]

  st = "OTHER"
  if (index($0, "STREAMING") > 0) st = "STREAMING"
  else if (index($0, "DISCONNECTED") > 0) st = "DISCONNECTED"
  else if (index($0, "CLOSED") > 0) st = "CLOSED"
  else if (index($0, "ERR=") > 0) st = "ERROR"
  else if (index($0, "OPENING") > 0) st = "OPENING"

  if (st != "STREAMING" && st != "OPENING") print "STATE  " id " " ts "  " $0

  if (id in lastf && s > lasts[id]) {
    rate = (f - lastf[id]) / (s - lasts[id])
    if (rate < 0) rate = 0
    if (!(id in minr) || rate < minr[id]) { minr[id] = rate; mint[id] = ts }
    if (!(id in maxr) || rate > maxr[id]) maxr[id] = rate
    if (rate < 10) print "STALL  " id " " ts "  rate=" sprintf("%.1f", rate) " fps  frames=" f "  " st
  }
  lastf[id] = f
  lasts[id] = s
  endst[id] = st
  endf[id] = f
}
END {
  print "---- 全程汇总 ----"
  for (i in lastf)
    print i "  末态=" endst[i] "  末帧=" endf[i] "  最低瞬时=" sprintf("%.1f", minr[i]) " fps (" mint[i] ")  最高=" sprintf("%.1f", maxr[i])
}
