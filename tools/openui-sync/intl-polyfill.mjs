// Lightweight ECMA-402 Intl fallback for QuickJS (Android/JVM) where Intl is absent.
// Only activates when globalThis.Intl is missing NumberFormat or DateTimeFormat.
const CURRENCY_SYMBOLS = {
  USD: "$",
  EUR: "€",
  GBP: "£",
  JPY: "¥",
  CNY: "¥",
  RMB: "¥",
  KRW: "₩",
  INR: "₹",
  CAD: "CA$",
  AUD: "A$",
  HKD: "HK$",
  TWD: "NT$",
  CHF: "CHF ",
  SGD: "S$",
};

const SHORT_MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
const LONG_MONTHS = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"];
const SHORT_DAYS = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
const LONG_DAYS = ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"];

function addGrouping(intPart) {
  return intPart.replace(/\B(?=(\d{3})+(?!\d))/g, ",");
}

function formatFixed(value, minFrac, maxFrac, useGrouping) {
  const fixed = value.toFixed(maxFrac);
  let [intPart, fracPart = ""] = fixed.split(".");
  while (fracPart.length > minFrac && fracPart.endsWith("0")) {
    fracPart = fracPart.slice(0, -1);
  }
  const grouped = useGrouping ? addGrouping(intPart) : intPart;
  return fracPart.length > 0 ? `${grouped}.${fracPart}` : grouped;
}

class FallbackNumberFormat {
  constructor(_locale, options = {}) {
    this.options = options || {};
  }

  format(raw) {
    const num = Number(raw);
    if (!Number.isFinite(num)) return String(raw);
    const {
      style = "decimal",
      currency,
      notation = "standard",
      useGrouping = true,
      minimumFractionDigits,
      maximumFractionDigits,
    } = this.options;

    const sign = num < 0 ? "-" : "";
    let abs = Math.abs(num);
    let suffix = "";

    if (style === "percent") {
      abs *= 100;
      suffix = "%";
    } else if (notation === "compact") {
      if (abs >= 1e12) {
        abs /= 1e12;
        suffix = "T";
      } else if (abs >= 1e9) {
        abs /= 1e9;
        suffix = "B";
      } else if (abs >= 1e6) {
        abs /= 1e6;
        suffix = "M";
      } else if (abs >= 1e3) {
        abs /= 1e3;
        suffix = "K";
      }
    }

    const defaultMax = style === "currency" ? 2 : style === "percent" ? 0 : notation === "compact" ? 1 : 3;
    const maxFrac = Math.min(20, Math.max(0, maximumFractionDigits ?? (minimumFractionDigits !== undefined ? Math.max(minimumFractionDigits, defaultMax) : defaultMax)));
    const minFrac = Math.min(maxFrac, Math.max(0, minimumFractionDigits ?? (style === "currency" && notation !== "compact" ? 2 : 0)));

    const body = formatFixed(abs, minFrac, maxFrac, useGrouping && notation !== "compact");
    if (style === "currency" && currency) {
      const code = String(currency).toUpperCase();
      const sym = CURRENCY_SYMBOLS[code] ?? `${code} `;
      return `${sign}${sym}${body}${suffix}`;
    }
    return `${sign}${body}${suffix}`;
  }
}

class FallbackDateTimeFormat {
  constructor(_locale, options = {}) {
    this.options = options || {};
  }

  format(raw) {
    const date = raw instanceof Date ? raw : new Date(raw);
    if (Number.isNaN(date.getTime())) return String(raw);
    const opts = this.options;
    const useUtc = opts.timeZone === "UTC";
    const year = useUtc ? date.getUTCFullYear() : date.getFullYear();
    const month = useUtc ? date.getUTCMonth() : date.getMonth();
    const day = useUtc ? date.getUTCDate() : date.getDate();
    const weekday = useUtc ? date.getUTCDay() : date.getDay();
    const hours24 = useUtc ? date.getUTCHours() : date.getHours();
    const minutes = useUtc ? date.getUTCMinutes() : date.getMinutes();

    const parts = [];
    if (opts.weekday) {
      parts.push(opts.weekday === "long" ? LONG_DAYS[weekday] : SHORT_DAYS[weekday]);
    }
    let dateStr = "";
    if (opts.month && opts.day) {
      const m = opts.month === "long" ? LONG_MONTHS[month] : opts.month === "2-digit" ? String(month + 1).padStart(2, "0") : SHORT_MONTHS[month];
      dateStr = `${m} ${day}`;
      if (opts.year) dateStr += `, ${year}`;
    } else if (opts.month && opts.year) {
      const m = opts.month === "long" ? LONG_MONTHS[month] : SHORT_MONTHS[month];
      dateStr = `${m} ${year}`;
    } else if (opts.month) {
      dateStr = opts.month === "long" ? LONG_MONTHS[month] : SHORT_MONTHS[month];
    } else if (opts.year) {
      dateStr = String(year);
    }
    if (dateStr) parts.push(dateStr);

    let timeStr = "";
    if (opts.hour || opts.minute) {
      const hour12 = opts.hour12 !== false;
      const minPad = String(minutes).padStart(2, "0");
      if (hour12) {
        const ampm = hours24 >= 12 ? "PM" : "AM";
        const h = hours24 % 12 || 12;
        timeStr = `${h}:${minPad} ${ampm}`;
      } else {
        timeStr = `${String(hours24).padStart(2, "0")}:${minPad}`;
      }
      parts.push(timeStr);
    }

    if (parts.length === 0) {
      return `${SHORT_MONTHS[month]} ${day}, ${year}`;
    }
    return parts.join(", ");
  }
}

export function ensureIntl() {
  let installedPolyfill = false;
  if (typeof globalThis.Intl === "undefined") {
    globalThis.Intl = {};
  }
  if (typeof globalThis.Intl.NumberFormat !== "function") {
    globalThis.Intl.NumberFormat = FallbackNumberFormat;
    installedPolyfill = true;
  }
  if (typeof globalThis.Intl.DateTimeFormat !== "function") {
    globalThis.Intl.DateTimeFormat = FallbackDateTimeFormat;
    installedPolyfill = true;
  }
  // QuickJS's built-in toLocaleString/toLocaleDateString ignore Intl options; delegate to our Intl polyfill.
  if (installedPolyfill) {
    Number.prototype.toLocaleString = function (locale, options) {
      return new globalThis.Intl.NumberFormat(locale, options).format(Number(this));
    };
    Date.prototype.toLocaleDateString = function (locale, options) {
      return new globalThis.Intl.DateTimeFormat(locale, options).format(this);
    };
    Date.prototype.toLocaleTimeString = function (locale, options) {
      return new globalThis.Intl.DateTimeFormat(locale, options || { hour: "numeric", minute: "2-digit" }).format(this);
    };
    Date.prototype.toLocaleString = function (locale, options) {
      return new globalThis.Intl.DateTimeFormat(locale, options).format(this);
    };
  }
}
