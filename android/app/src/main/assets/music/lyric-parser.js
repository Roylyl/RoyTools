'use strict';
// Simple LRC offset is in milliseconds: effective time = timestamp - offset.
// Positive values show lyrics sooner; negative values delay them. Apply once here.
// Reference: https://github.com/Clarkkkk/paroles#lyricsinfo
(function (root) {
  function parseLrc(input, title = '', artist = '') {
    const source = String(input || '').replace(/^\uFEFF/, '');
    const rows = [], metadata = [];
    const offsetTag = source.match(/^\s*\[offset:\s*([+-]?\d+)\s*\]/im);
    const offset = offsetTag ? Number(offsetTag[1]) : 0;
    let order = 0;
    for (const raw of source.split(/\r?\n/)) {
      const line = raw.trim();
      if (!line || /^https?:\/\//i.test(line)) continue;
      if (/^(?:\[(?:ti|ar|al|au|length|by|re|tool|ve|offset|id|#):[^\]]*\]\s*)+$/i.test(line)) continue;
      const tag = /\[(\d+):([0-5]\d)(?:\.(\d{1,3}))?\]/g;
      const matches = [...line.matchAll(tag)];
      // Only time tags are removed; ordinary square brackets are lyric text.
      const body = line.replace(tag, '').trim();
      if (!body || body === `${title} - ${artist}`) continue;
      if (matches.length && rows.length===0 && Number(matches[0][1])*60+Number(matches[0][2])<=3 && body.startsWith(title) && body.endsWith(' - '+artist)) continue;
      if (/^(?:词|曲|词曲|编曲|制作|配唱制作|监制|演唱|和声|演奏|吉他|木吉他|电吉他|低音吉他|贝斯|鼓|打击乐|键盘|钢琴|风琴|合成器|口琴|小提琴|中提琴|大提琴|弦乐|弦乐编写|长笛|萨克斯|小号|长号|指挥|编程|童声合唱|录音|录音助理|录音棚|录音环境|混音|母带|乐器\d*|词 Lyricist|曲 Composer|管弦乐编写|音乐总监|管弦乐|Program工程操作|Program制作|Program录音|混音\/母带工作室)\s*[:：]/i.test(body)) {
        if (!metadata.includes(body)) metadata.push(body);
        continue;
      }
      if (matches.length) for (const match of matches) {
        const fraction = match[3] ? Number(match[3].padEnd(3, '0')) : 0;
        rows.push({time: (Number(match[1]) * 60000 + Number(match[2]) * 1000 + fraction - offset) / 1000, text: body, order: order++});
      } else rows.push({time: null, text: body, order: order++});
    }
    rows.sort((a,b) => (a.time ?? Infinity) - (b.time ?? Infinity) || a.order - b.order);
    const groups = [];
    for (const row of rows) {
      const previous = groups.at(-1);
      if (row.time !== null && previous?.time === row.time) previous.lines.push(row.text);
      else groups.push({time:row.time,lines:[row.text]});
    }
    return {groups,metadata,diagnostic: groups.length ? (groups.some(g => g.time !== null) ? 'timed' : 'plain') : metadata.length ? 'credits' : 'empty'};
  }
  function currentLyricGroup(groups, time) {
    let result = -1;
    for (let i=0;i<groups.length;i++) {
      if (groups[i].time === null) continue;
      if (groups[i].time > time) break;
      result = i;
    }
    return result;
  }
  root.RoylylLyrics = {parseLrc,currentLyricGroup};
  if (typeof module !== 'undefined') module.exports = root.RoylylLyrics;
})(typeof self !== 'undefined' ? self : globalThis);
