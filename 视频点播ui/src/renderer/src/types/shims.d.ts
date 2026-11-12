declare module 'spark-md5' {
  interface SparkMD5ArrayBuffer {
    append(data: ArrayBuffer | string): void
    end(raw?: boolean): string
    reset(): void
  }

  const SparkMD5: {
    ArrayBuffer: new () => SparkMD5ArrayBuffer
  }

  export default SparkMD5
}
