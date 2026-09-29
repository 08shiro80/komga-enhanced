/**
 * Returns the URL only if it is a safe external http(s) link, otherwise undefined.
 * Guards :href bindings against javascript:/data: URL injection from external sources.
 */
export function safeExternalHref(url: string | null | undefined): string | undefined {
  if (!url) return undefined
  return /^https?:\/\//i.test(url.trim()) ? url : undefined
}

/**
 * Dependency-free HTML sanitizer using the browser-native DOMParser: strips script/style/iframe/object/
 * embed elements, all on* event-handler attributes, and javascript:/data: hrefs. Not as exhaustive as a
 * dedicated library, but covers the common XSS vectors for the low-trust markdown we render (release notes).
 */
export function sanitizeHtml(html: string): string {
  const doc = new DOMParser().parseFromString(html, 'text/html')
  doc.querySelectorAll('script, style, iframe, object, embed').forEach((el) => el.remove())
  doc.querySelectorAll('*').forEach((el) => {
    for (const attr of [...el.attributes]) {
      const name = attr.name.toLowerCase()
      const value = attr.value.replace(/\s+/g, '').toLowerCase()
      const isUrlAttr = name === 'href' || name === 'src' || name === 'xlink:href'
      if (name.startsWith('on') || (isUrlAttr && (value.startsWith('javascript:') || value.startsWith('data:')))) {
        el.removeAttribute(attr.name)
      }
    }
  })
  return doc.body.innerHTML
}
