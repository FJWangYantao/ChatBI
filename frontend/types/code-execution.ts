// frontend/types/code-execution.ts
export interface CodeExecution {
  executionId: string;
  stage: string;
  code: string;
  stdout: string;
  stderr: string;
  success: boolean;
  executionTime: number;
}
