const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function numberedSeat(concertId, number) {
  if (!uuid.test(concertId) || !Number.isSafeInteger(number) || number < 1 || number > 10000) {
    throw new Error('Choose a valid concert and seat number.');
  }
  return async request => {
    let selected;
    let heartbeatAt = Date.now() + 30000;
    for (let offset = 0; offset <= 10000; offset += 500) {
      if (Date.now() >= heartbeatAt) {
        await request('POST', '/api/v1/admission/heartbeat');
        heartbeatAt = Date.now() + 30000;
      }
      const rows = await request('GET', `/api/v1/concerts/${concertId}/seats?limit=500&offset=${offset}`);
      if (!Array.isArray(rows) || rows.length > 500) throw new Error('Invalid seat response.');
      if (offset === 10000 && rows.length) throw new Error('Concert seat selection exceeds the supported limit.');
      for (const seat of rows) {
        if (!seat || !uuid.test(seat.id) || !Number.isSafeInteger(seat.number) || seat.number < 1
            || !['AVAILABLE', 'RESERVED', 'SOLD'].includes(seat.status)) {
          throw new Error('Invalid seat response.');
        }
        if (seat.number === number) {
          if (selected) throw new Error('Seat number is ambiguous. Choose a concert with unique seat numbers.');
          selected = seat;
        }
      }
      if (rows.length < 500) {
        if (!selected || selected.status !== 'AVAILABLE') throw new Error('Seat unavailable.');
        return selected.id;
      }
    }
    throw new Error('Seat unavailable.');
  };
}
