/** The recruiter access code lives in sessionStorage: it survives reloads but not closing the tab. */
const KEY = "triage.accessCode";

export function loadAccessCode(): string | null {
  try {
    return sessionStorage.getItem(KEY);
  } catch {
    return null;
  }
}

export function saveAccessCode(code: string): void {
  try {
    sessionStorage.setItem(KEY, code);
  } catch {
    /* storage blocked: the code still works for this page view */
  }
}

export function clearAccessCode(): void {
  try {
    sessionStorage.removeItem(KEY);
  } catch {
    /* ignore */
  }
}
