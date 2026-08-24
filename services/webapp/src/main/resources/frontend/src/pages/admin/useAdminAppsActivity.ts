import { useCallback, useReducer } from 'react';

type ActivityKey =
  | 'saving'
  | 'inspecting'
  | 'discoveringWebsite'
  | 'applying'
  | 'generatingDescription'
  | 'deletingSelected'
  | 'exportingCsv'
  | 'deletingAll'
  | 'retryingSelected';

interface ActivityState extends Record<ActivityKey, boolean> {
  message: string | null;
  error: string | null;
}

type ActivityAction =
  | { type: 'operation'; key: ActivityKey; value: boolean }
  | { type: 'message'; value: string | null }
  | { type: 'error'; value: string | null };

const INITIAL_ACTIVITY: ActivityState = {
  message: null,
  error: null,
  saving: false,
  inspecting: false,
  discoveringWebsite: false,
  applying: false,
  generatingDescription: false,
  deletingSelected: false,
  exportingCsv: false,
  deletingAll: false,
  retryingSelected: false,
};

function activityReducer(state: ActivityState, action: ActivityAction): ActivityState {
  switch (action.type) {
    case 'operation':
      return { ...state, [action.key]: action.value };
    case 'message':
      return { ...state, message: action.value };
    case 'error':
      return { ...state, error: action.value };
  }
}

/** Agrupa las operaciones asíncronas del banco administrativo en una máquina de estado. */
export function useAdminAppsActivity() {
  const [state, dispatch] = useReducer(activityReducer, INITIAL_ACTIVITY);
  const setMessage = useCallback((value: string | null) => {
    dispatch({ type: 'message', value });
  }, []);
  const setError = useCallback((value: string | null) => {
    dispatch({ type: 'error', value });
  }, []);
  const setOperation = useCallback((key: ActivityKey, value: boolean) => {
    dispatch({ type: 'operation', key, value });
  }, []);

  return {
    ...state,
    setMessage,
    setError,
    setSaving: useCallback((value: boolean) => setOperation('saving', value), [setOperation]),
    setInspecting: useCallback((value: boolean) => setOperation('inspecting', value), [setOperation]),
    setDiscoveringWebsite: useCallback(
      (value: boolean) => setOperation('discoveringWebsite', value),
      [setOperation],
    ),
    setApplying: useCallback((value: boolean) => setOperation('applying', value), [setOperation]),
    setGeneratingDescription: useCallback(
      (value: boolean) => setOperation('generatingDescription', value),
      [setOperation],
    ),
    setDeletingSelected: useCallback(
      (value: boolean) => setOperation('deletingSelected', value),
      [setOperation],
    ),
    setExportingCsv: useCallback(
      (value: boolean) => setOperation('exportingCsv', value),
      [setOperation],
    ),
    setDeletingAll: useCallback(
      (value: boolean) => setOperation('deletingAll', value),
      [setOperation],
    ),
    setRetryingSelected: useCallback(
      (value: boolean) => setOperation('retryingSelected', value),
      [setOperation],
    ),
  };
}
