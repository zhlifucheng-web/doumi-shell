// Preserve real collection gaps while allowing slow SSH responses to arrive.
export function monitorSeries(rows, pick) {
  const values=[]
  let previous
  for (const row of rows) {
    const cadence=Math.max(previous?.sampleIntervalMs||5000,row.sampleIntervalMs||5000)
    if(previous&&(row.breakBefore||row.timestamp-previous.timestamp>Math.max(60000,cadence*4)))values.push([previous.timestamp+1,null])
    values.push([row.timestamp,pick(row)??null])
    previous=row
  }
  return values
}
