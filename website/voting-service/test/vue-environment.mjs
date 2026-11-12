import { inject } from 'vue'

export function useData() {
  return { lang: inject('test-language') }
}

export function withBase(path) {
  return '/mihon-desktop' + path
}
